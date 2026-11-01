package com.betterschool.marketing.infrastructure.inventory.merge;

/**
 * 合并器快照读数（spec §2.7，字段名与计数侧 {@code CountMerger} 那份逐字相同）。
 *
 * <p><b>只是 record，不依赖 micrometer、不开端点</b>：本工程没有 metrics 端点，读数进启动/停机日志与
 * 发布记录。四个用途：
 * <ul>
 *   <li>{@code pending}：背压预警（某分片长期高位 ⇒ 该加 flush 线程或该降级了）；</li>
 *   <li>{@code accepted − flushed}：丢没丢（再减 {@code failed + drainedFailed} 才是「还在途」）；</li>
 *   <li>{@code flushed ÷ batches}：<b>合并比</b>，调参唯一依据（≈1 说明长尾，合并器是净成本）；</li>
 *   <li>{@code rejectedFull / rejectedClosed / timedOut / failed / drainedFailed / maxFlushNanos}：
 *       降级、失败与「落库是不是快到还能给窗口腾地方」。库存侧 {@code timedOut} 就是
 *       {@code TIMEOUT_UNKNOWN} 率，{@code rejectedFull} 就是「同步降级发生了多少次」（不是错误，是容量信号）。</li>
 * </ul>
 *
 * @param pending       当前缓冲件数（全分片求和，无锁镜像）
 * @param accepted      成功入队件数
 * @param flushed       落库方法回填了业务结果的件数。<b>库存侧含 {@code INSUFFICIENT}</b>——
 *                      它是业务结果（余量不足，确定没扣），不是丢失；只有 {@code failed} 才是真丢
 * @param batches       落库方法被调用的<b>组</b>数（一组一个事务；合并比 = flushed ÷ batches）
 * @param rejectedFull  因分片满而未入队的件数
 * @param rejectedClosed 因停机而未入队的件数
 * @param timedOut      等超时的件数（这些件之后可能仍成功落库，故不参与守恒）
 * @param failed        落库抛异常 / 漏回填被补齐的件数
 * @param drainedFailed 停机排空超时被显式回填的件数
 * @param maxFlushNanos 单组落库的最长耗时（对照 {@code waitTimeoutMs} 用）
 */
public record MergeMetrics(int pending,
                           long accepted,
                           long flushed,
                           long batches,
                           long rejectedFull,
                           long rejectedClosed,
                           long timedOut,
                           long failed,
                           long drainedFailed,
                           long maxFlushNanos) {
}
