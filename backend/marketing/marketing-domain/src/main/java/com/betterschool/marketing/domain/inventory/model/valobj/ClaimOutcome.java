package com.betterschool.marketing.domain.inventory.model.valobj;

/**
 * 兑换确认（{@code submitAndWait(InventoryClaimRequestVO)}）的出口。
 *
 * <p><b>只有两条业务出口</b>：成功（含「撞 {@code uk_biz_type} = 已确认过 = 按成功回放」）与可重试。
 * 没有 {@code INSUFFICIENT} —— 确认路径上「{@code waiting} 不够」不是业务失败，是<b>不变量破坏</b>：
 * 预扣 happens-before 中奖事件，确认不可能抢在预扣落账之前，所以守卫不过只能是账不平。
 * 落库方法在那种情况下整组回滚并抛出，合并器把它回填成 {@link #FAILED}（票 07 §3）。
 *
 * <p><b>{@code TIMEOUT_UNKNOWN} 与 {@code FAILED} 在确认路径上可以合并处理</b>（都抛可重试）：
 * 幂等键把它兜住了——超时的件可能稍后落、也可能没落，MQ 重投要么撞 {@code uk} 回放、要么补上。
 * 与预扣不同：预扣是用户付钱的那一下，必须当场分出第三态（见 {@link DeductResult}）。
 *
 * <p>{@link #BUFFER_FULL} / {@link #CLOSED} 的处理与预扣一致：调用方线程同步
 * {@code confirmIssued(invId, List.of(item))}，复用同一个落库方法，组大小 1。
 */
public enum ClaimOutcome {

    /** 成功：{@code waiting -= c, issued += c} 已提交，{@code type = 2} 明细已落（含撞键回放） */
    CONFIRMED,

    /** 等超时：件仍在缓冲或正在落库，可能已确认 ⇒ 抛可重试，靠幂等键重投放行 */
    TIMEOUT_UNKNOWN,

    /** 分片缓冲已满：件未入队 ⇒ 调用方线程同步确认一件 */
    BUFFER_FULL,

    /** 合并器已停机：件未入队 ⇒ 同上 */
    CLOSED,

    /** 落库失败 / 漏回填 / 排空回填（含 {@code waiting} 不足导致的整组回滚）⇒ 抛可重试 */
    FAILED
}
