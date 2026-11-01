package com.betterschool.marketing.infrastructure.inventory.merge;

/**
 * 入队结果：引擎内部的三句话，<b>不出本包</b>。
 *
 * <p>与计数侧同名枚举的差别只有一处：库存侧没有 fire-and-forget 的调用方（每一个预扣 / 确认都在等
 * 结果），所以它不需要对外的 {@code submitAsync}，这个三态就地翻译成 {@code DeductResult} /
 * {@code ClaimOutcome} 的同名项（{@code BUFFER_FULL} / {@code CLOSED} / 失败）。
 */
enum SubmitOutcome {

    /** 已入队，窗口到点后落库 */
    ACCEPTED,

    /** 分片缓冲已满：未入队 */
    BUFFER_FULL,

    /** 合并器已停机：未入队 */
    CLOSED
}
