package com.betterschool.usecase.trade;

import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductFundOrphanScanSummaryVO;
import com.betterschool.domain.trade.model.valobj.ProductOrderEventVO;
import com.betterschool.domain.trade.model.valobj.ProductOrderStatusVO;
import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品资金孤儿扫描（ADR-0016 两段式的收敛 job 用例，照 {@code ErrandFundOrphanScanCase} /
 * {@code drawCountPurchaseScan} 口径：cron 0/30、无状态窗口重扫、动作全部流水唯一键幂等——
 * 失败行留在窗口内下轮重扫，无 per-row 状态与死信）。
 *
 * <p>两段式资金链路的宕机/失败窗口留下的长款中间态在此收敛，判据全部是「流水行与业务行状态比对」，零新增状态表：
 * <ul>
 *   <li>钱包侧自查（PRODUCT_FROZEN 与 PRODUCT_UNFROZEN 同表比对，与跑腿 FROZEN/UNFROZEN
 *       同表不同 type 的两条扫描线）：有 PRODUCT_FROZEN、同订单同轮次同人无 PRODUCT_UNFROZEN、
 *       冻结已超阈值 → 查订单裁决：终态（CANCELLED/COMPLETED）即补解冻退买家；未终态
 *       （支付翻转在途 / 交付流转中）不动钱——此刻解冻会让后续翻转成功后出现「已支付但没冻结」的短款，
 *       宁可长款等到终态再退；</li>
 *   <li>订单侧自查（近窗口终态订单重放资金动作）：CANCELLED 且最新事件为 CANCELLED、
 *       from_state = PAID（已支付取消，与取消链路 needRefund 口径一致）补买家解冻退款
 *       （PRODUCT_UNFROZEN 流水 uk 幂等）；待支付取消 / 支付超时关单未冻结，无资金动作可补；
 *       确认收货路径不改（幂等回放口径已收敛，ADR-0015 明确保留）。</li>
 * </ul>
 *
 * <p>跨域只读与幂等重放都在本层顺序编排（仓库纪律：case 层跨域组合顺序调用、不包事务）；
 * 每个动作单调用单事务，失败异常整轮上抛由 xxl 记账、下个周期照常重扫（未收敛行永远满足扫描条件）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductFundOrphanScanCase {

    /** 冻结孤儿判定阈值（分钟）：翻转段正常在秒级完成，超阈值仍「PRODUCT_FROZEN 无 PRODUCT_UNFROZEN」才视为孤儿 */
    private static final int FROZEN_ORPHAN_THRESHOLD_MINUTES = 10;

    /** 终态订单重放窗口（分钟）：覆盖「翻转已提交、资金段未执行」的窗口，与跑腿孤儿扫描 10 分钟窗口口径一致 */
    private static final int SETTLED_WINDOW_MINUTES = 10;

    /** 单轮每段最多处理的行数：孤儿是异常路径，正常流量下恒 0 行 */
    private static final int SCAN_LIMIT = 200;

    /** 取消事件类型字面量（与仓储写入侧 saveEvent 同口径） */
    private static final String EVENT_CANCELLED = "CANCELLED";

    private final IWalletRepository walletRepository;
    private final ITradeRepository tradeRepository;

    public ProductFundOrphanScanSummaryVO scan() {
        List<WalletDetailEntity> frozenOrphans = walletRepository.listOrphanFrozen(
                WalletDetailTypeVO.PRODUCT_FROZEN, WalletDetailTypeVO.PRODUCT_UNFROZEN,
                FROZEN_ORPHAN_THRESHOLD_MINUTES, SCAN_LIMIT);
        List<ProductOrderEntity> settledOrders = tradeRepository.listRecentSettled(SETTLED_WINDOW_MINUTES, SCAN_LIMIT);
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
            ProductOrderEntity orderEntity = tradeRepository.findOrderById(frozenEntity.getOrderId());
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
            ProductOrderEventVO latestEvent = tradeRepository.latestEventOf(orderEntity.getOrderId());
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
