package com.betterschool.infrastructure.merge.count;

/**
 * 合并器终态：等结果的那条入口（{@code commit}）<b>只会说出三句</b>——{@code COMPLETED} /
 * {@code TIMEOUT} / {@code FAILED}。
 *
 * <p>刻意<b>不出现 {@code REJECTED}</b>：合并器的「没接住」不再交给调用方处理。缓冲满 / 停机中意味着
 * 件从未入队、必未落库，引擎当场用<b>同一个落库方法</b>在本线程落组大小 1
 * （{@code CountMerger#degrade}），结论仍是下面三态之一——降级只写一份，调用方不必自己判断。
 * 剩下两个「未入队」常量留着，是为了让这件事在读代码时有名字，也让 {@code switch} 保持穷尽。
 *
 * <ul>
 *   <li>{@link #COMPLETED}：落库方法已回填业务结果（点赞成功 / 撞唯一键 / 取消失败等）；</li>
 *   <li>{@link #TIMEOUT}：件还在缓冲或正在落库，<b>之后大概率仍会落库</b>——库存侧必须表达为
 *       「处理中」而不是失败，计数侧回 {@code RETRYABLE}；同步降级漏回填（事务可能已提交）也归这一态；</li>
 *   <li>{@link #BUFFER_FULL}：分片满，件<b>从未入队</b>——引擎内部态，{@code commit} 就地降级，不外返；</li>
 *   <li>{@link #CLOSED}：停机窗口，件<b>从未入队</b>——同上；</li>
 *   <li>{@link #FAILED}：落库抛异常、落库漏回填被补齐、或排空超时被显式回填（含降级时抛异常）。</li>
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
