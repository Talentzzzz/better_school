package com.betterschool.usecase.idle;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductCancelResult;
import com.betterschool.domain.idle.model.valobj.ProductConfirmResult;
import com.betterschool.domain.idle.model.valobj.ProductFundOrphanScanSummaryVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusEnum;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.domain.wallet.service.IWalletPaymentService;
import com.betterschool.types.concurrent.VirtualThreadFanout;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 闲置域用例编排：发布、商品托管订单、资金孤儿扫描三段，一个域一个编排入口。
 *
 * <p>跨域组合顺序调用（每个域各自事务原子，事务边界 = 仓储，ADR-0016），一致性靠
 * 「幂等 + 重试」——状态机条件 UPDATE / wallet 流水 uk_order_type 保证重试安全，本层不做事务。
 *
 * <p>三段的分界不是组织偏好，是把该域所有跨域动作收在一处：发布段向信用域要门槛，
 * 订单段向钱包域要平台结算 / 平台退款（买家支付时已由支付渠道完成「买家出账 + 平台入账」），
 * 扫描段读平台腿流水做补偿——任何一处改了资金口径，另外两处的判据都在同一文件里，不会各走各的。
 *
 * <p>支付走支付渠道（wallet 模拟微信支付）：本类直接注入钱包域的 {@link IWalletPaymentService}
 * 编排「下单后预下单 / 取消与超时关支付单」，不注入其他 case——case 之间不互相注入，
 * 跨域顺序各自在本层排完。收银台按支付单号支付、支付单查询与支付回调重放属渠道侧入口，
 * 在 {@code PaymentCase}；钱包回调本域翻转订单仍走 {@code IPaymentNotifyPort}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdleCase {

    /** 平台孤儿判定阈值（分钟）：翻转段正常在秒级完成，超阈值仍「有 PLATFORM_IN 无 PLATFORM_OUT」才视为孤儿 */
    private static final int PLATFORM_ORPHAN_THRESHOLD_MINUTES = 10;

    /** 单轮最多处理的行数：孤儿是异常路径，正常流量下恒 0 行 */
    private static final int SCAN_LIMIT = 200;

    /** 收银台标题长度上限（payment_order.subject） */
    private static final int SUBJECT_MAX_LENGTH = 100;

    private final IIdleService idleService;
    private final ICreditAccountService creditAccountService;
    private final IWalletAccountService walletAccountService;
    private final IWalletPaymentService walletPaymentService;
    private final VirtualThreadFanout fanout;

    // ==================== 发布：信用分门槛前置校验 ====================

    public String publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                                 String tradeLocation, String category, Integer quantity) {
        creditAccountService.ensureOperable(userId);
        return idleService.publishProduct(userId, title, description, images, price, tradeLocation, category, quantity);
    }

    /** 保存商品草稿：不做信用分门槛，字段可缺省 */
    public String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                                   String tradeLocation, String category, Integer quantity) {
        return idleService.saveProductDraft(userId, title, description, images, price, tradeLocation, category, quantity);
    }

    /** 编辑商品草稿：仅 DRAFT 可编辑 */
    public void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                                   BigDecimal price, String tradeLocation, String category, Integer quantity) {
        idleService.updateProductDraft(userId, productId, title, description, images, price,
                tradeLocation, category, quantity);
    }

    /** 提交商品草稿：与直接发布同门槛校验（信用分前置），归属/状态守卫与完整性校验在域内裁决，通过即上架可见 */
    public void submitProductDraft(String userId, String productId) {
        creditAccountService.ensureOperable(userId);
        idleService.submitProductDraft(userId, productId);
    }

    /** 上架后编辑商品（编辑语义）：归属与状态守卫、落编辑都在域内裁决，立即生效 */
    public void editOnSaleProduct(String userId, String productId, String title, String description, List<String> images,
                                  BigDecimal price) {
        idleService.editOnSaleProduct(userId, productId, title, description, images, price);
    }

    /**
     * 卖家加货（multi-quantity-listing）：台账 quantity/remaining 同涨 + RESTOCK 流水（同事务）。
     * 归属与在售守卫由域层 getOwnedOnSaleProduct 前置
     */
    public void restockProduct(String userId, String productId, int quantity) {
        idleService.restockProduct(userId, productId, quantity);
    }

    /** 上架商品主动下架：ON_SALE → DRAFT 汇入草稿箱，可编辑后重新提交上架；同事务退出搜索可见 */
    public void takeDownProduct(String userId, String productId) {
        idleService.takeDownProduct(userId, productId);
    }

    /**
     * 到期下架扫描（XXL-Job 每分钟）：上架满 30 天（expire_at，重提/改内容时刷新）的商品汇入草稿箱而非终态。
     * 条件 UPDATE 幂等，与下架并发无害。单发一批、批内扇出（宽度 = SCAN_BATCH_SIZE），项内失败只影响该项（下轮扫描补跑）
     */
    public void scanExpiredListings() {
        fanout.forEach("idle-expire-product-", idleService.listExpiredProductIds(),
                idleService::expireProductToDraft);
    }

    // ==================== 商品托管订单：跨域资金编排 ====================

    public String createOrder(String buyerId, String productId, int quantity) {
        creditAccountService.ensureOperable(buyerId);
        String orderId = idleService.createOrder(buyerId, productId, quantity);
        // 下单后向钱包预下单拿支付单号：best-effort 策略在钱包域（失败只记日志返回 null），
        // 预下单失败不拖垮已成立的订单，订单详情 / 支付前会按同一 bizOrderId 幂等补齐
        ProductOrderEntity productOrderEntity = idleService.getOrderEntity(orderId);
        walletPaymentService.createPaymentOrderQuietly(buyerId, orderId,
                productOrderEntity.totalAmount(), subjectOf(productOrderEntity));
        return orderId;
    }

    /**
     * 订单详情页懒创建：确保待支付订单的支付单存在并返回支付单号（按 bizOrderId 幂等）。
     * 仅买家、仅待支付订单可预下单；其余状态返回 null。
     */
    public String ensurePayNo(ProductOrderEntity productOrderEntity, String userId) {
        if (productOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在");
        }
        if (!productOrderEntity.isBuyer(userId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能支付自己的订单");
        }
        if (productOrderEntity.getStatus() != ProductOrderStatusEnum.PENDING_PAYMENT) {
            return null;
        }
        PaymentOrderEntity paymentOrderEntity = walletPaymentService.createPaymentOrder(userId,
                productOrderEntity.getOrderId(), productOrderEntity.totalAmount(), subjectOf(productOrderEntity));
        return paymentOrderEntity == null ? null : paymentOrderEntity.getPayNo();
    }

    /**
     * 内部便捷入口：按商户订单号支付（测试 / 内部调用）。先做买家与可支付态守卫，
     * 再按支付单号走同一支付链路；已支付及之后的订单幂等返回。
     */
    public void payOrder(String orderId, String buyerId) {
        ProductOrderEntity productOrderEntity = idleService.preparePayOrder(orderId, buyerId);
        if (productOrderEntity.getStatus() != ProductOrderStatusEnum.PENDING_PAYMENT) {
            return; // 幂等：上次支付已提交 / 订单已流转
        }
        PaymentOrderEntity paymentOrderEntity = walletPaymentService.createPaymentOrder(buyerId, orderId,
                productOrderEntity.totalAmount(), subjectOf(productOrderEntity));
        walletPaymentService.pay(paymentOrderEntity.getPayNo(), buyerId);
    }

    public void markDelivered(String orderId, String sellerId) {
        idleService.markDelivered(orderId, sellerId);
    }

    /**
     * 确认收货：域服务只翻订单状态（DELIVERED → COMPLETED，单仓储事务），本层随后执行一次
     * 平台结算（平台出账 + 卖家入账，单仓储事务、双边流水）。买家余额在支付时已扣，
     * 此步不再变化；重复确认由卖家 PRODUCT_INCOME 流水唯一键幂等吸收。
     */
    public void confirmReceived(String orderId, String buyerId) {
        ProductConfirmResult productConfirmResult = idleService.confirmReceived(orderId, buyerId);
        walletAccountService.settleProductOrder(orderId, productConfirmResult.getSellerId(), productConfirmResult.getAmount());
    }

    /**
     * 取消（两段式 ADR-0016）：域服务只做取消翻转段（含台账回补 + 搜索同步，单仓储事务，
     * 翻转段先行——翻转未命中抛异常、资金段尚未执行零残留），本层按结果对象的退款需求
     * 顺序执行平台退款（平台出账 + 买家入账，PRODUCT_REFUND / PLATFORM_OUT 双边流水，
     * 流水 uk_order_type 幂等）；翻转与退款之间的宕机孤儿（订单已取消、平台已入账却未出账）
     * 由 {@code productFundOrphanScan} 扫描收敛；待支付取消（平台从未收到钱）needRefund=false
     * 无资金动作，重放终态短路不重复退款
     */
    public void cancelOrder(String orderId, String buyerId) {
        ProductCancelResult productCancelResult = idleService.cancelOrder(orderId, buyerId);
        if (productCancelResult.isNeedRefund()) {
            walletAccountService.refundProductOrder(orderId, productCancelResult.getBuyerId(), productCancelResult.getAmount());
        }
        // 支付前取消：关闭支付单，避免旧支付单被继续支付（已支付订单 close 是空操作，退款走上面的平台退款）
        walletPaymentService.closePaymentOrderQuietly(orderId);
    }

    public ProductOrderEntity orderDetail(String orderId, String currentUserId) {
        return idleService.orderDetail(orderId, currentUserId);
    }

    public List<ProductOrderEntity> listMineOrders(String role, String userId, int page, int pageSize) {
        return idleService.listMineOrders(role, userId, page, pageSize);
    }

    public long countMineOrders(String role, String userId) {
        return idleService.countMineOrders(role, userId);
    }

    /**
     * 支付超时：只取消 {@code PENDING_PAYMENT} 订单并关闭支付单，无资金动作——平台从未收到这笔钱，
     * 此刻退款会给买家凭空入账（平台余额守卫会失败）。
     * <p>
     * 「支付已进平台、订单却因超时被取消」这个并发窗口（晚到支付）由 {@code productFundOrphanScan}
     * 按「平台有入账无出账」收敛：订单已 CANCELLED，扫描下一轮补平台退款给买家。
     */
    public void handlePayTimeout(String orderId) {
        idleService.handlePayTimeout(orderId);
        // 超时关单后关闭支付单：晚到的收银台请求按「支付单已关闭」拒绝（失败策略在钱包域）
        walletPaymentService.closePaymentOrderQuietly(orderId);
    }

    /** 收银台展示标题：商品标题 + 件数，超长截断（payment_order.subject 列宽） */
    private String subjectOf(ProductOrderEntity productOrderEntity) {
        String title = productOrderEntity.getProductTitle() == null ? "商品订单" : productOrderEntity.getProductTitle();
        int quantity = productOrderEntity.getQuantity() == null ? 1 : productOrderEntity.getQuantity();
        String subject = quantity > 1 ? title + " × " + quantity : title;
        return subject.length() > SUBJECT_MAX_LENGTH ? subject.substring(0, SUBJECT_MAX_LENGTH) : subject;
    }

    // ==================== 资金孤儿扫描：平台代收账「有入账无出账」的收敛 job ====================

    /**
     * 商品资金孤儿扫描（ADR-0016 两段式的收敛 job 用例，照 {@code drawCountPurchaseScan} 口径：
     * cron 0/30、无状态窗口重扫、动作全部流水唯一键幂等——
     * 失败行留在窗口内下轮重扫，无 per-row 状态与死信）。
     *
     * <p>判据只有一条平台线索：有 {@code PLATFORM_IN}、同订单同轮次无 {@code PLATFORM_OUT}、
     * 且入账已超阈值，即「平台已收钱，却既没结算也没退款」。支付、结算、退款都会为同一订单
     * 成对写平台腿，所以这一条线索覆盖了原先「冻结 / 解冻比对」与「终态订单重放」两段扫描的全部场景。
     *
     * <p>裁决口径「宁可长款」：订单 {@code COMPLETED} 补结算、{@code CANCELLED} 补退款，
     * 其余状态（支付在途 / 已支付待交付）不动钱——订单还没完成就退款会造出短款，
     * 长款等到终态后下一轮自然满足条件。收款方一律取自订单（买家 / 卖家），不能取流水行的
     * {@code user_id}——它现在是平台账户。订单缺失记 ERROR 跳过、不动钱。
     *
     * <p>跨域只读与幂等重放都在本层顺序编排（仓库纪律：case 层跨域组合顺序调用、不包事务）；
     * 每个动作单调用单事务，失败异常整轮上抛由 xxl 记账、下个周期照常重扫（未收敛行永远满足扫描条件）。
     */
    public ProductFundOrphanScanSummaryVO scanFundOrphans() {
        List<WalletDetailEntity> platformInOrphans = walletAccountService.listOrphanPlatformIn(
                PLATFORM_ORPHAN_THRESHOLD_MINUTES, SCAN_LIMIT);
        int settleDone = 0;
        int refundDone = 0;
        for (WalletDetailEntity walletDetailEntity : platformInOrphans) {
            ProductOrderEntity productOrderEntity = idleService.getOrderEntity(walletDetailEntity.getOrderId());
            if (productOrderEntity == null) {
                log.error("[资金孤儿] 平台入账流水无对应订单，需人工核查：orderId={}", walletDetailEntity.getOrderId());
                continue;
            }
            if (productOrderEntity.getStatus() == ProductOrderStatusEnum.COMPLETED) {
                // 终态补结算：平台出账 + 卖家入账；幂等锚是卖家 PRODUCT_INCOME，重扫不重复入账
                walletAccountService.settleProductOrder(productOrderEntity.getOrderId(), productOrderEntity.getSellerId(),
                        productOrderEntity.totalAmount());
                settleDone++;
                log.warn("[资金孤儿] 已完成订单补结算：orderId={}, sellerId={}, amount={}",
                        productOrderEntity.getOrderId(), productOrderEntity.getSellerId(), productOrderEntity.totalAmount());
            } else if (productOrderEntity.getStatus() == ProductOrderStatusEnum.CANCELLED) {
                // 终态补退款：平台出账 + 买家入账；幂等锚是买家 PRODUCT_REFUND，重扫不重复退款
                walletAccountService.refundProductOrder(productOrderEntity.getOrderId(), productOrderEntity.getBuyerId(),
                        productOrderEntity.totalAmount());
                refundDone++;
                log.warn("[资金孤儿] 已取消订单补退款：orderId={}, buyerId={}, amount={}",
                        productOrderEntity.getOrderId(), productOrderEntity.getBuyerId(), productOrderEntity.totalAmount());
            }
            // 其余状态（PENDING_PAYMENT / PAID / DELIVERED）：不动钱，宁可长款等到终态再收敛
        }
        return new ProductFundOrphanScanSummaryVO(platformInOrphans.size(), settleDone, refundDone);
    }
}
