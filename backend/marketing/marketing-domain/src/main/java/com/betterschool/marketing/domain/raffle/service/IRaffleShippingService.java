package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.model.valobj.ShippingPlanVO;
import com.betterschool.marketing.domain.raffle.model.valobj.ShippingResultVO;

/**
 * 发货状态机（票 14）：draw_record.state 推进的**唯一入口**，每推进一步同事务落一条
 * {@link com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordActionVO} 动作明细。
 * <p>
 * 状态全图（状态 × 动作 × 落明细）：
 * <pre>
 *   中奖落库        DRAW      → 待兑换（THANKS 直接 已完成，无后续动作）
 *   收到中奖事件 VIRTUAL/DRAW_COUNT
 *                   EXCHANGE  → 已兑换
 *                   COMPLETE  → 已完成      （同一事务两条明细，一步到位）
 *   收到中奖事件 PHYSICAL
 *                   SHIP      → 已发货      （跨域兑换确认排在它**之前**，见下）
 *   用户确认收货 PHYSICAL
 *                   COMPLETE  → 已完成
 * </pre>
 * 幂等：推进靠 {@code UPDATE ... WHERE record_id = ? AND state = 起点} 的条件更新——重投 / 重放
 * 只会在守卫上落空（返回 0），明细侧另有 {@code uk_record_type} 兜底，故不会重复推进、不会重复落明细。
 * <p>
 * **两步编排（实物档必须这样用）**：实物配额在另外一域，确认动作要在推进之前登记（票 11 起它已是一次
 * 本地写，不再同步判 {@code waiting_qty}），而「该确认什么」只能从「记录仍在待兑换」这个读里算出来：
 * <ol>
 *   <li>{@link #planShipAfterDraw} —— **只读**，判定有没有事可做、要不要先确认（不改任何状态）</li>
 *   <li>调用方按 {@code plan.quotaToConfirm} 做库存兑换确认（非 null 时才做）</li>
 *   <li>{@link #advanceShipAfterDraw} —— 真正推进状态并落明细</li>
 * </ol>
 * 两步拆开之后，「先确认、后推进」不需要调用方额外记住顺序也能正确重放：库存侧按明细唯一键幂等，
 * 抽奖侧靠起点条件落空，两段各自可重复执行。
 */
public interface IRaffleShippingService {

    /**
     * 中奖事件的处置计划（只读）：记录不存在 / 不属本人 / 已不在待兑换 / 谢谢参与档时
     * 返回 {@code active=false}（调用方直接返回，两步都不做）。
     */
    ShippingPlanVO planShipAfterDraw(String userId, Long recordId);

    /**
     * 按计划推进状态：虚拟 / 次数档 → 已完成（EXCHANGE + COMPLETE）；实物档 → 已发货（SHIP）。
     * 计划已失效（并发另一笔先推进）时返回 {@code advanced=false}，不抛异常。
     */
    ShippingResultVO advanceShipAfterDraw(String userId, ShippingPlanVO plan);

    /**
     * 用户确认收货：已发货 → 已完成（实物档终态）。记录不属本人 / 状态不是已发货时抛业务异常。
     * 单域动作：由 trigger 直调，不经 case 层编排（没有跨域写）。
     */
    ShippingResultVO confirmReceived(String userId, Long recordId);
}
