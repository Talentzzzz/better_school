package com.betterschool.usecase.idle;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductConfirmResult;
import com.betterschool.domain.idle.model.valobj.ProductFundOrphanScanSummaryVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderEventVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusEnum;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeEnum;
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
 * 订单段向钱包域要冻结/解冻/入账，扫描段读钱包流水做补偿——任何一处改了资金口径，
 * 另外两处的判据都在同一文件里，不会各走各的。
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

    /** 冻结孤儿判定阈值（分钟）：翻转段正常在秒级完成，超阈值仍「PRODUCT_FROZEN 无 PRODUCT_UNFROZEN」才视为孤儿 */
    private static final int FROZEN_ORPHAN_THRESHOLD_MINUTES = 10;

    /** 终态订单重放窗口（分钟）：覆盖「翻转已提交、资金段未执行」的窗口，与冻结孤儿阈值同口径 */
    private static final int SETTLED_WINDOW_MINUTES = 10;

    /** 单轮每段最多处理的行数：孤儿是异常路径，正常流量下恒 0 行 */
    private static final int SCAN_LIMIT = 200;

    /** 收银台标题长度上限（payment_order.subject） */
    private static final int SUBJECT_MAX_LENGTH = 100;

    /** 取消事件类型字面量（与仓储写入侧 saveEvent 同口径） */
    private static final String EVENT_CANCELLED = "CANCELLED";

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

    public void confirmReceived(String orderId, String buyerId) {
        ProductConfirmResult productConfirmResult = idleService.confirmReceived(orderId, buyerId);
        walletAccountService.unfreeze(productConfirmResult.getBuyerId(), productConfirmResult.getAmount(), orderId,
                WalletDetailTypeEnum.PRODUCT_UNFROZEN);
        walletAccountService.income(productConfirmResult.getSellerId(), productConfirmResult.getAmount(), orderId,
                WalletDetailTypeEnum.PRODUCT_INCOME);
    }

    /**
     * 取消（两段式 ADR-0016）：域服务只做取消翻转段（含台账回补 + 搜索同步，单仓储事务，
     * 翻转段先行——翻转未命中抛异常、资金段尚未执行零残留），本层按结果对象的退款需求
     * 顺序执行解冻退款（PRODUCT_UNFROZEN，流水 uk_order_type 幂等）；
     * 翻转与解冻之间的宕机孤儿（订单已取消但货款悬置冻结态）由 {@code productFundOrphanScan}
     * 扫描收敛；待支付取消（未冻结）needRefund=false 无资金动作，重放终态短路不重复退款
     */
    public void cancelOrder(String orderId, String buyerId) {
        var cancelResult = idleService.cancelOrder(orderId, buyerId);
        if (cancelResult.isNeedRefund()) {
            walletAccountService.unfreeze(cancelResult.getBuyerId(), cancelResult.getAmount(), orderId,
                    WalletDetailTypeEnum.PRODUCT_UNFROZEN);
        }
        // 支付前取消：关闭支付单，避免旧支付单被继续支付（已支付订单 close 是空操作，退款走上面的解冻）
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

    // ==================== 资金孤儿扫描：两段式链路的收敛 job ====================

    /**
     * 商品资金孤儿扫描（ADR-0016 两段式的收敛 job 用例，照 {@code drawCountPurchaseScan} 口径：
     * cron 0/30、无状态窗口重扫、动作全部流水唯一键幂等——
     * 失败行留在窗口内下轮重扫，无 per-row 状态与死信）。
     *
     * <p>两段式资金链路的宕机/失败窗口留下的长款中间态在此收敛，判据全部是「流水行与业务行状态比对」，零新增状态表：
     * <ul>
     *   <li>钱包侧自查（PRODUCT_FROZEN 与 PRODUCT_UNFROZEN 同表比对，同表不同 type 的扫描线）：
     *       有 PRODUCT_FROZEN、同订单同轮次同人无 PRODUCT_UNFROZEN、冻结已超阈值 → 查订单裁决：
     *       终态（CANCELLED/COMPLETED）即补解冻退买家；未终态（支付翻转在途 / 交付流转中）不动钱——
     *       此刻解冻会让后续翻转成功后出现「已支付但没冻结」的短款，宁可长款等到终态再退；</li>
     *   <li>订单侧自查（近窗口终态订单重放资金动作）：CANCELLED 且最新事件为 CANCELLED、
     *       from_state = PAID（已支付取消，与取消链路 needRefund 口径一致）补买家解冻退款
     *       （PRODUCT_UNFROZEN 流水 uk 幂等）；待支付取消 / 支付超时关单未冻结，无资金动作可补；
     *       确认收货路径不改（幂等回放口径已收敛，ADR-0015 明确保留）。</li>
     * </ul>
     *
     * <p>跨域只读与幂等重放都在本层顺序编排（仓库纪律：case 层跨域组合顺序调用、不包事务）；
     * 每个动作单调用单事务，失败异常整轮上抛由 xxl 记账、下个周期照常重扫（未收敛行永远满足扫描条件）。
     */
    public ProductFundOrphanScanSummaryVO scanFundOrphans() {
        List<WalletDetailEntity> frozenOrphans = walletAccountService.listOrphanFrozen(
                WalletDetailTypeEnum.PRODUCT_FROZEN, WalletDetailTypeEnum.PRODUCT_UNFROZEN,
                FROZEN_ORPHAN_THRESHOLD_MINUTES, SCAN_LIMIT);
        List<ProductOrderEntity> settledOrders = idleService.listRecentSettled(SETTLED_WINDOW_MINUTES, SCAN_LIMIT);
        int orphanUnfrozenDone = unfreezeTerminalOrphans(frozenOrphans);
        int replayUnfrozenDone = replayPaidCancelUnfreeze(settledOrders);
        return new ProductFundOrphanScanSummaryVO(frozenOrphans.size(), orphanUnfrozenDone, replayUnfrozenDone);
    }

    // ==================== 资金孤儿扫描：两段式链路的收敛 job ====================
    private int unfreezeTerminalOrphans(List<WalletDetailEntity> frozenOrphans) {
        int done = 0;
        for (WalletDetailEntity walletDetailEntity : frozenOrphans) {
            ProductOrderEntity productOrderEntity = idleService.getOrderEntity(walletDetailEntity.getOrderId());
            if (productOrderEntity == null) {
                log.error("[资金孤儿] 商品冻结流水无对应订单，需人工核查：orderId={}", walletDetailEntity.getOrderId());
                continue;
            }
            ProductOrderStatusEnum productOrderStatusVO = productOrderEntity.getStatus();
            if (productOrderStatusVO != ProductOrderStatusEnum.CANCELLED && productOrderStatusVO != ProductOrderStatusEnum.COMPLETED) {
                // 未终态（支付翻转在途 / PAID 交付流转中）：不动钱——支付链路冻结先行是常态在途，
                // 此刻解冻，随后翻转成功会造出「已支付但没冻结」的短款；长款等到终态后下一轮自然满足条件
                continue;
            }
            walletAccountService.unfreeze(walletDetailEntity.getUserId(), walletDetailEntity.getAmount(),
                    walletDetailEntity.getOrderId(), WalletDetailTypeEnum.PRODUCT_UNFROZEN, walletDetailEntity.getBizRound());
            done++;
            log.warn("[资金孤儿] 终态商品订单补解冻退款：orderId={}, state={}, amount={}, bizRound={}",
                    walletDetailEntity.getOrderId(), productOrderStatusVO, walletDetailEntity.getAmount(), walletDetailEntity.getBizRound());
        }
        return done;
    }

    /**
     * 第二段（订单侧自查 + 事件行裁决）：近窗口 CANCELLED 订单，最新事件为 CANCELLED
     * 且 from_state = PAID（已支付取消，与取消链路 needRefund 口径一致）时补买家解冻退款
     * （PRODUCT_UNFROZEN 流水 uk 幂等吸收重复）。待支付取消 / 支付超时关单（from_state =
     * PENDING_PAYMENT）从未冻结，无资金动作可补。
     */
    private int replayPaidCancelUnfreeze(List<ProductOrderEntity> settledOrders) {
        int done = 0;
        for (ProductOrderEntity productOrderEntity : settledOrders) {
            if (productOrderEntity.getStatus() != ProductOrderStatusEnum.CANCELLED) {
                continue; // COMPLETED 的资金段（解冻 + 卖家入账）走确认收货幂等回放口径，本扫描不改
            }
            ProductOrderEventVO productOrderEventVO = idleService.latestEventOf(productOrderEntity.getOrderId());
            if (productOrderEventVO == null || !EVENT_CANCELLED.equals(productOrderEventVO.getEventType())) {
                continue;
            }
            if (!"PAID".equals(productOrderEventVO.getFromState())) {
                continue;
            }
            int quantity = productOrderEntity.getQuantity() == null ? 1 : productOrderEntity.getQuantity();
            BigDecimal totalAmount = productOrderEntity.getPrice().multiply(BigDecimal.valueOf(quantity));
            walletAccountService.unfreeze(productOrderEntity.getBuyerId(), totalAmount, productOrderEntity.getOrderId(),
                    WalletDetailTypeEnum.PRODUCT_UNFROZEN);
            done++;
        }
        return done;
    }
}
