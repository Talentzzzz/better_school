package com.betterschool.infrastructure.adapter.merge;

/**
 * 不等结果的那条入口（{@code submitAsync}，浏览类）的返回：只有三句，没有 {@code TIMEOUT}
 * ——不等的人不会超时，也不分配 future。
 *
 * <p>调用方拿到 {@code ACCEPTED} 就可以立刻返回给用户；拿到另外两个就按 case 层口径处理
 * （浏览类：丢弃并 {@code droppedViews++}）。
 */
public enum SubmitOutcome {

    /** 已入队，窗口到点后落库 */
    ACCEPTED,

    /** 分片缓冲已满：未入队 */
    BUFFER_FULL,

    /** 合并器已停机：未入队 */
    CLOSED
}
