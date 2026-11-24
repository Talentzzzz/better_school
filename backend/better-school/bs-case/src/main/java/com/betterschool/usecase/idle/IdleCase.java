package com.betterschool.usecase.idle;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.idle.adapter.repository.IIdleRepository;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductCancelResult;
import com.betterschool.domain.idle.model.valobj.ProductConfirmResult;
import com.betterschool.domain.idle.model.valobj.ProductFundOrphanScanSummaryVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderEventVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusVO;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.types.concurrent.VirtualThreadFanout;
import com.betterschool.types.enums.ResponseCode;
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
 * <p>支付与取消为两段式资金链路：支付资金段（冻结）先行、订单翻转在后；取消翻转段先行、
 * 本层补解冻退款；宕机窗口的长款中间态由扫描段（{@code productFundOrphanScan}）收敛。
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

    /** 取消事件类型字面量（与仓储写入侧 saveEvent 同口径） */
    private static final String EVENT_CANCELLED = "CANCELLED";

    private final IIdleService idleService;
    private final ICreditAccountService creditAccountService;
    private final IWalletAccountService walletAccountService;
    private final IWalletRepository walletRepository;
    private final IIdleRepository idleRepository;
    private final VirtualThreadFanout fanout;

    // ==================== 发布：信用分门槛前置校验 + 草稿完整性 ====================

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

    /** 提交商品草稿：与直接发布同门槛校验，通过即上架可见 */
    public void submitProductDraft(String userId, String productId) {
        ProductEntity draftEntity = idleService.getOwnedProductDraft(userId, productId);
        creditAccountService.ensureOperable(userId);
        validateProductDraftComplete(draftEntity);
        idleService.submitProductDraft(userId, productId);
    }

    /** 上架后编辑商品（编辑语义）：先做归属与状态守卫，再由域层落编辑、立即生效 */
    public void editOnSaleProduct(String userId, String productId, String title, String description, List<String> images,
                                  BigDecimal price) {
        idleService.getOwnedOnSaleProduct(userId, productId);
        idleService.editOnSaleProduct(productId, title, description, images, price);
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

    private void validateProductDraftComplete(ProductEntity draftEntity) {
        if (isBlank(draftEntity.getTitle()) || draftEntity.getPrice() == null
                || isBlank(draftEntity.getTradeLocation()) || draftEntity.getCategory() == null) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿内容不完整，请补全标题、价格、交易地点与分类后再提交");
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    // ==================== 商品托管订单：跨域资金编排 ====================

    public String createOrder(String buyerId, String productId, int quantity) {
        creditAccountService.ensureOperable(buyerId);
        return idleService.createOrder(buyerId, productId, quantity);
    }

    /**
     * 支付（两段式 ADR-0016）：① 域服务 prepare 校验（买家/状态幂等，返回需冻结货款）→
     * ② 本层资金段冻结货款（PRODUCT_FROZEN，流水 uk_order_type 幂等 + 余额条件扣减，
     * 余额不足抛异常、订单留待支付）→ ③ 域服务 confirm 翻转「待支付 → 已支付」（单仓储事务）。
     * 资金段恒先行（宁可长款不可短款）：翻转失败时订单留待支付、钱悬置冻结，
     * 孤儿由 {@code productFundOrphanScan} 扫描解冻收敛。客户端重试安全：prepare 幂等回放 +
     * 冻结流水 uk 吸收重复冻结
     */
    public void payOrder(String orderId, String buyerId) {
        var payResult = idleService.preparePayOrder(orderId, buyerId);
        if (payResult.isAlreadyPaid()) {
            return; // 幂等：上次支付已提交成功（重试场景），零副作用
        }
        walletAccountService.freeze(buyerId, payResult.getAmount(), orderId, WalletDetailTypeVO.PRODUCT_FROZEN);
        idleService.confirmPayOrder(orderId, buyerId);
    }

    public void markDelivered(String orderId, String sellerId) {
        idleService.markDelivered(orderId, sellerId);
    }

    public void confirmReceived(String orderId, String buyerId) {
        ProductConfirmResult confirmResult = idleService.confirmReceived(orderId, buyerId);
        walletAccountService.unfreeze(confirmResult.getBuyerId(), confirmResult.getAmount(), orderId,
                WalletDetailTypeVO.PRODUCT_UNFROZEN);
        walletAccountService.income(confirmResult.getSellerId(), confirmResult.getAmount(), orderId,
                WalletDetailTypeVO.PRODUCT_INCOME);
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
                    WalletDetailTypeVO.PRODUCT_UNFROZEN);
        }
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
        List<WalletDetailEntity> frozenOrphans = walletRepository.listOrphanFrozen(
                WalletDetailTypeVO.PRODUCT_FROZEN, WalletDetailTypeVO.PRODUCT_UNFROZEN,
                FROZEN_ORPHAN_THRESHOLD_MINUTES, SCAN_LIMIT);
        List<ProductOrderEntity> settledOrders = idleRepository.listRecentSettled(SETTLED_WINDOW_MINUTES, SCAN_LIMIT);
        int orphanUnfrozenDone = unfreezeTerminalOrphans(frozenOrphans);
        int replayUnfrozenDone = replayPaidCancelUnfreeze(settledOrders);
        return new ProductFundOrphanScanSummaryVO(frozenOrphans.size(), orphanUnfrozenDone, replayUnfrozenDone);
    }

    /**
     * 第一段（钱包侧自查 → 订单裁决）：有 PRODUCT_FROZEN 无 PRODUCT_UNFROZEN 且超阈值的行，
     * 订单已终态即补解冻退买家（解冻流水 uk(order_id, type, biz_round, user_id) 幂等吸收重复）。
     */
    private int unfreezeTerminalOrphans(List<WalletDetailEntity> frozenOrphans) {
        int done = 0;
        for (WalletDetailEntity frozenEntity : frozenOrphans) {
            ProductOrderEntity orderEntity = idleRepository.findOrderById(frozenEntity.getOrderId());
            if (orderEntity == null) {
                log.error("[资金孤儿] 商品冻结流水无对应订单，需人工核查：orderId={}", frozenEntity.getOrderId());
                continue;
            }
            ProductOrderStatusVO status = orderEntity.getStatus();
            if (status != ProductOrderStatusVO.CANCELLED && status != ProductOrderStatusVO.COMPLETED) {
                // 未终态（支付翻转在途 / PAID 交付流转中）：不动钱——支付链路冻结先行是常态在途，
                // 此刻解冻，随后翻转成功会造出「已支付但没冻结」的短款；长款等到终态后下一轮自然满足条件
                continue;
            }
            walletRepository.unfreeze(frozenEntity.getUserId(), frozenEntity.getAmount(),
                    frozenEntity.getOrderId(), WalletDetailTypeVO.PRODUCT_UNFROZEN, frozenEntity.getBizRound());
            done++;
            log.warn("[资金孤儿] 终态商品订单补解冻退款：orderId={}, state={}, amount={}, bizRound={}",
                    frozenEntity.getOrderId(), status, frozenEntity.getAmount(), frozenEntity.getBizRound());
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
        for (ProductOrderEntity orderEntity : settledOrders) {
            if (orderEntity.getStatus() != ProductOrderStatusVO.CANCELLED) {
                continue; // COMPLETED 的资金段（解冻 + 卖家入账）走确认收货幂等回放口径，本扫描不改
            }
            ProductOrderEventVO latestEvent = idleRepository.latestEventOf(orderEntity.getOrderId());
            if (latestEvent == null || !EVENT_CANCELLED.equals(latestEvent.getEventType())) {
                continue;
            }
            if (!"PAID".equals(latestEvent.getFromState())) {
                continue;
            }
            int quantity = orderEntity.getQuantity() == null ? 1 : orderEntity.getQuantity();
            BigDecimal totalAmount = orderEntity.getPrice().multiply(BigDecimal.valueOf(quantity));
            walletRepository.unfreeze(orderEntity.getBuyerId(), totalAmount, orderEntity.getOrderId(),
                    WalletDetailTypeVO.PRODUCT_UNFROZEN);
            done++;
        }
        return done;
    }
}
