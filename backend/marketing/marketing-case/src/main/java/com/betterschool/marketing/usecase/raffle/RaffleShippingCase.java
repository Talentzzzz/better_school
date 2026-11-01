package com.betterschool.marketing.usecase.raffle;

import com.betterschool.marketing.domain.raffle.adapter.port.inventory.IQuotaConfirmPort;
import com.betterschool.marketing.domain.raffle.model.valobj.ShippingPlanVO;
import com.betterschool.marketing.domain.raffle.model.valobj.ShippingResultVO;
import com.betterschool.marketing.domain.raffle.service.IRaffleShippingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 发货闭环编排（票 14 case 层）：**跨域写组合的顺序调用，无跨域事务**（CLAUDE.md §1）。
 * <p>
 * 顺序（三步都可重复执行；失败靠 MQ 重投）：
 * <ol>
 *   <li>抽奖域状态机先算「该确认什么」（**只读**，不推进）</li>
 *   <li>库存域兑换确认：落一条 {@code type = 2} 明细 + 一条合并消息（本地写，转移由合并完成）</li>
 *   <li>抽奖域状态推进：{@code draw_record.state} + 动作明细（票 14 状态机，本地事务）</li>
 * </ol>
 * **顺序保留，但已不再承重**（票 11 起）：兑换确认不再同步判 {@code waiting_qty}、不会因为合并还没跑而失败，
 * 所以它和状态推进之间没有「谁先谁后决定对错」的关系了。保留「先确认、后推进」没有代价，读起来也仍是
 * 业务时序；真正兜底的是两段各自的幂等——库存侧撞明细唯一键当成功回放，抽奖侧状态机在起点条件上落空。
 * <p>
 * 去重靠两处：库存的 {@code uk_biz_type (type, biz_type, biz_id)}（同一条记录只会有一行 {@code type = 2}）
 * 与抽奖域的状态起点（记录不在待兑换就不再确认）。所以「确认已提交、推进未提交」之间崩溃时，重投会
 * 再确认一次并撞唯一键——**零副作用**，不再有「确认失败 → 抛错 → 重投」那套剧情。
 * <p>
 * 只被 MQ 这条边用（HTTP 的「确认收货」是单域动作，由 trigger 直调 {@link IRaffleShippingService}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleShippingCase {

    private final IRaffleShippingService raffleShippingService;
    private final IQuotaConfirmPort quotaConfirmPort;

    /**
     * 中奖事件驱动的发货：实物档**先**做库存兑换确认、**再**推进状态到已发货；
     * 虚拟 / 次数档只推进状态（入账由消费同一事件的积分域负责）。
     */
    public ShippingResultVO shipAfterDraw(String userId, Long recordId) {
        ShippingPlanVO plan = raffleShippingService.planShipAfterDraw(userId, recordId);
        if (!plan.isActive()) {
            // 重复投递 / 已终态 / 谢谢参与 / 查无记录：三步都不做
            return new ShippingResultVO(recordId, null, false);
        }
        ShippingPlanVO.QuotaConfirmVO quota = plan.getQuotaToConfirm();
        if (quota != null) {
            // 一次本地写（type = 2 明细 + 合并消息）：不判 waiting、不会因合并没跑而失败，转移交给合并
            quotaConfirmPort.confirmIssued(quota.getInvId(), recordId, quota.getQuantity());
            log.info("[RaffleShippingCase] 实物配额兑换确认已落明细 recordId={} invId={}", recordId, quota.getInvId());
        }
        return raffleShippingService.advanceShipAfterDraw(userId, plan);
    }
}
