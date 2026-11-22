package com.betterschool.usecase.errand;

import com.betterschool.domain.credit.model.valobj.CreditReasonTypeVO;
import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandFundOrphanScanSummaryVO;
import com.betterschool.domain.errand.model.valobj.ErrandOrderEventVO;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 跑腿资金孤儿扫描（ADR-0016 两段式的收敛 job 用例，照 {@code drawCountPurchaseScan} 口径：
 * cron 0/30、无状态窗口重扫、动作全部流水唯一键幂等——失败行留在窗口内下轮重扫，无 per-row 状态与死信）。
 *
 * <p>两段式资金链路的宕机/失败窗口留下的长款中间态在此收敛，判据全部是「流水行与业务行状态比对」，零新增状态表：
 * <ul>
 *   <li>钱包侧自查（FROZEN 与 UNFROZEN 同表比对）：有 FROZEN、同订单同轮次同人无 UNFROZEN、冻结已超阈值
 *       → 查订单裁决：终态（CANCELLED/COMPLETED）即补解冻退发布方；未终态（支付翻转在途）不动钱——
 *       此时解冻会让后续支付翻转成功后出现「已发布但没冻结」的短款，宁可长款等到支付超时关单后再退；</li>
 *   <li>订单侧自查（近窗口终态订单重放资金动作）：COMPLETED 补接单方悬赏入账（INCOME 流水 uk 幂等）；
 *       CANCELLED 且最新事件为 TIMEOUT、from_state ∈ {CONFIRMED, PICKED_UP}（已开工违约）补接单方信用分扣减
 *       （uk_user_reason_ref 幂等）。</li>
 * </ul>
 *
 * <p>跨域只读与幂等重放都在本层顺序编排（仓库纪律：case 层跨域组合顺序调用、不包事务）；
 * 每个动作单调用单事务，失败异常整轮上抛由 xxl 记账、下个周期照常重扫（未收敛行永远满足扫描条件）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrandFundOrphanScanCase {

    /** 冻结孤儿判定阈值（分钟）：翻转段正常在秒级完成，超阈值仍「FROZEN 无 UNFROZEN」才视为孤儿 */
    private static final int FROZEN_ORPHAN_THRESHOLD_MINUTES = 10;

    /** 终态订单重放窗口（分钟）：覆盖「翻转已提交、资金段未执行」的窗口，与 ADR-0015 抽奖孤儿 10 分钟种单口径一致 */
    private static final int SETTLED_WINDOW_MINUTES = 10;

    /** 单轮每段最多处理的行数：孤儿是异常路径，正常流量下恒 0 行 */
    private static final int SCAN_LIMIT = 200;

    /** 超时取消事件类型字面量（与仓储写入侧 saveEvent 同口径） */
    private static final String EVENT_TIMEOUT = "TIMEOUT";

    private final IWalletRepository walletRepository;
    private final IErrandRepository errandRepository;
    private final ICreditAccountService creditAccountService;

    public ErrandFundOrphanScanSummaryVO scan() {
        List<WalletDetailEntity> frozenOrphans = walletRepository.listOrphanFrozen(
                FROZEN_ORPHAN_THRESHOLD_MINUTES, SCAN_LIMIT);
        List<ErrandOrderEntity> settledOrders = errandRepository.listRecentSettled(SETTLED_WINDOW_MINUTES, SCAN_LIMIT);
        int unfrozenDone = unfreezeTerminalOrphans(frozenOrphans);
        int incomeDone = replayCompletedIncome(settledOrders);
        int creditDone = replayTimeoutCredit(settledOrders);
        return new ErrandFundOrphanScanSummaryVO(frozenOrphans.size(), unfrozenDone, incomeDone, creditDone);
    }

    /**
     * 第一段（钱包侧自查 → 订单裁决）：有 FROZEN 无 UNFROZEN 且超阈值的行，
     * 订单已终态即补解冻退发布方（解冻流水 uk(order_id, type, biz_round, user_id) 幂等吸收重复）。
     */
    private int unfreezeTerminalOrphans(List<WalletDetailEntity> frozenOrphans) {
        int done = 0;
        for (WalletDetailEntity frozenEntity : frozenOrphans) {
            ErrandOrderEntity orderEntity = errandRepository.findById(frozenEntity.getOrderId());
            if (orderEntity == null) {
                log.error("[资金孤儿] 冻结流水无对应订单，需人工核查：orderId={}", frozenEntity.getOrderId());
                continue;
            }
            ErrandStatusVO status = orderEntity.getStatus();
            if (status != ErrandStatusVO.CANCELLED && status != ErrandStatusVO.COMPLETED) {
                // 未终态（支付翻转在途）：不动钱——此刻解冻，随后支付翻转成功会造出「已发布但没冻结」的短款；
                // 长款等支付超时关单后下一轮自然满足终态条件
                continue;
            }
            walletRepository.unfreeze(frozenEntity.getUserId(), frozenEntity.getAmount(),
                    frozenEntity.getOrderId(), WalletDetailTypeVO.UNFROZEN, frozenEntity.getBizRound());
            done++;
            log.warn("[资金孤儿] 终态订单补解冻退款：orderId={}, state={}, amount={}, bizRound={}",
                    frozenEntity.getOrderId(), status, frozenEntity.getAmount(), frozenEntity.getBizRound());
        }
        return done;
    }

    /**
     * 第二段之入账（订单侧自查）：近窗口 COMPLETED 订单补接单方悬赏入账
     * （确认完成 / 仲裁判完成 / 自动完成三条路径共用同一结算口径，INCOME 流水 uk 幂等吸收重复）。
     */
    private int replayCompletedIncome(List<ErrandOrderEntity> settledOrders) {
        int done = 0;
        for (ErrandOrderEntity orderEntity : settledOrders) {
            if (orderEntity.getStatus() != ErrandStatusVO.COMPLETED || orderEntity.getTakerId() == null) {
                continue;
            }
            walletRepository.income(orderEntity.getTakerId(), orderEntity.getReward(),
                    orderEntity.getOrderId(), WalletDetailTypeVO.INCOME);
            done++;
        }
        return done;
    }

    /**
     * 第二段之扣分（订单侧自查 + 事件行裁决）：近窗口 CANCELLED 订单，最新事件为 TIMEOUT
     * 且 from_state ∈ {CONFIRMED, PICKED_UP}（已承诺后违约，与超时取消的 committed 口径一致）时
     * 补接单方信用分扣减（uk_user_reason_ref 幂等吸收重复）。
     */
    private int replayTimeoutCredit(List<ErrandOrderEntity> settledOrders) {
        int done = 0;
        for (ErrandOrderEntity orderEntity : settledOrders) {
            if (orderEntity.getStatus() != ErrandStatusVO.CANCELLED || orderEntity.getTakerId() == null) {
                continue;
            }
            ErrandOrderEventVO latestEvent = errandRepository.latestEventOf(orderEntity.getOrderId());
            if (latestEvent == null || !EVENT_TIMEOUT.equals(latestEvent.getEventType())) {
                continue;
            }
            String fromState = latestEvent.getFromState();
            boolean committed = "CONFIRMED".equals(fromState) || "PICKED_UP".equals(fromState);
            if (!committed) {
                continue;
            }
            creditAccountService.applyChange(orderEntity.getTakerId(), CreditReasonTypeVO.ERRAND_TIMEOUT,
                    orderEntity.getOrderId());
            done++;
        }
        return done;
    }
}
