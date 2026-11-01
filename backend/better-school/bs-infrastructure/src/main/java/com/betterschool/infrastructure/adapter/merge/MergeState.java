package com.betterschool.infrastructure.adapter.merge;

/**
 * 等结果的那条入口（{@code submitAndWait}）能拿到的五种终态——合并器只说这五句。
 *
 * <p>刻意<b>不出现 {@code REJECTED}</b>：降级动作（缓冲满 / 停机中 → 调用方线程同步调同一个落库方法）
 * 发生在 case 层，不在引擎里（spec §2.6）。引擎把「没接住」如实分成
 * {@code BUFFER_FULL} / {@code CLOSED} 两种，剩下的交给调用方决定怎么说给用户听。
 *
 * <ul>
 *   <li>{@link #COMPLETED}：落库方法已回填业务结果（点赞成功 / 撞唯一键 / 取消失败等）；</li>
 *   <li>{@link #TIMEOUT}：件还在缓冲或正在落库，<b>之后大概率仍会落库</b>——库存侧必须表达为
 *       「处理中」而不是失败，计数侧回 {@code RETRYABLE}；</li>
 *   <li>{@link #BUFFER_FULL}：分片满，件<b>从未入队</b>，可以安全地同步降级；</li>
 *   <li>{@link #CLOSED}：停机窗口，件<b>从未入队</b>；</li>
 *   <li>{@link #FAILED}：落库抛异常、落库漏回填被补齐、或排空超时被显式回填。</li>
 * </ul>
 */
public enum MergeState {

    /** 落库方法已给出业务结果 */
    COMPLETED,

    /** 等超时：结果未知，件仍在途（大概率会落库） */
    TIMEOUT,

    /** 分片缓冲已满：件未入队 */
    BUFFER_FULL,

    /** 合并器已停机：件未入队 */
    CLOSED,

    /** 落库失败 / 漏回填 / 排空超时：件不会再有第二次机会 */
    FAILED
}
