package com.betterschool.marketing.domain.inventory.model.valobj;

/**
 * 兑换确认的业务出口：<b>只有两条</b>——成功（含「撞 {@code uk_award_biz} = 已确认过 = 按成功回放」）
 * 与可重试。
 *
 * <p>没有 {@code INSUFFICIENT}：确认路径上「{@code waiting} 不够」不是业务失败，是<b>不变量破坏</b>——
 * 预扣 happens-before 中奖事件，确认不可能抢在预扣落账之前，所以守卫不过只能是账不平。
 * 落库方法在那种情况下整组回滚并抛出，合并器把它归到失败态，域侧看到 {@link #RETRY_LATER}。
 *
 * <p>「还在处理中」与「确定没确认」也合并成 {@link #RETRY_LATER}：幂等键把它兜住了——
 * 超时的件可能稍后落、也可能没落，MQ 重投要么撞 {@code uk} 回放、要么补上。
 */
public enum ClaimOutcome {

    /** 成功：{@code waiting -= c, issued += c} 已提交，{@code flow_type = 2} 明细已落（含撞键回放） */
    CONFIRMED,

    /** 可能已确认或确定没确认 ⇒ 抛可重试，靠幂等键重投放行 */
    RETRY_LATER
}
