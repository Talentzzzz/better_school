package com.betterschool.infrastructure.adapter.merge;

import java.util.Objects;

/**
 * 合并器快照读数（spec §2.7）。
 *
 * <p><b>只是值对象，不依赖 micrometer、不开端点</b>：本工程没有 metrics 端点，读数进启动日志与发布
 * 记录。四个用途：
 * <ul>
 *   <li>{@code pending}：背压预警（某分片长期高位 ⇒ 该加 flush 线程或该降级了）；</li>
 *   <li>{@code accepted − flushed}：丢没丢（再减 {@code failed + drainedFailed} 才是「还在途」）；</li>
 *   <li>{@code flushed ÷ batches}：<b>合并比</b>，调参唯一依据（≈1 说明长尾，合并器是净成本）；</li>
 *   <li>{@code rejectedFull / rejectedClosed / timedOut / failed / drainedFailed / maxFlushNanos}：
 *       降级、失败与「落库是不是快到还能给窗口腾地方」。</li>
 * </ul>
 *
 * @param pending       当前缓冲件数（全分片求和，无锁镜像）
 * @param accepted      成功入队件数
 * @param flushed       落库方法回填为成功的件数
 * @param batches       落库方法被调用的<b>组</b>数（一组一个事务；合并比 = flushed ÷ batches）
 * @param rejectedFull  因分片满而未入队的件数
 * @param rejectedClosed 因停机而未入队的件数
 * @param timedOut      等超时的件数（这些件之后可能仍成功落库，故不参与守恒）
 * @param failed        落库抛异常 / 漏回填被补齐的件数
 * @param drainedFailed 停机排空超时被显式回填的件数（含浏览件的空回填）
 * @param maxFlushNanos 单组落库的最长耗时（对照 {@code waitTimeoutMs} 用）
 */
public final class MergeMetrics {

    private final int pending;
    private final long accepted;
    private final long flushed;
    private final long batches;
    private final long rejectedFull;
    private final long rejectedClosed;
    private final long timedOut;
    private final long failed;
    private final long drainedFailed;
    private final long maxFlushNanos;

    public MergeMetrics(int pending,
                        long accepted,
                        long flushed,
                        long batches,
                        long rejectedFull,
                        long rejectedClosed,
                        long timedOut,
                        long failed,
                        long drainedFailed,
                        long maxFlushNanos) {
        this.pending = pending;
        this.accepted = accepted;
        this.flushed = flushed;
        this.batches = batches;
        this.rejectedFull = rejectedFull;
        this.rejectedClosed = rejectedClosed;
        this.timedOut = timedOut;
        this.failed = failed;
        this.drainedFailed = drainedFailed;
        this.maxFlushNanos = maxFlushNanos;
    }

    public int pending() {
        return pending;
    }

    public long accepted() {
        return accepted;
    }

    public long flushed() {
        return flushed;
    }

    public long batches() {
        return batches;
    }

    public long rejectedFull() {
        return rejectedFull;
    }

    public long rejectedClosed() {
        return rejectedClosed;
    }

    public long timedOut() {
        return timedOut;
    }

    public long failed() {
        return failed;
    }

    public long drainedFailed() {
        return drainedFailed;
    }

    public long maxFlushNanos() {
        return maxFlushNanos;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MergeMetrics other)) return false;
        return pending == other.pending
                && accepted == other.accepted
                && flushed == other.flushed
                && batches == other.batches
                && rejectedFull == other.rejectedFull
                && rejectedClosed == other.rejectedClosed
                && timedOut == other.timedOut
                && failed == other.failed
                && drainedFailed == other.drainedFailed
                && maxFlushNanos == other.maxFlushNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pending, accepted, flushed, batches, rejectedFull, rejectedClosed,
                timedOut, failed, drainedFailed, maxFlushNanos);
    }

    @Override
    public String toString() {
        return "MergeMetrics[pending=" + pending + ", accepted=" + accepted + ", flushed=" + flushed
                + ", batches=" + batches + ", rejectedFull=" + rejectedFull + ", rejectedClosed=" + rejectedClosed
                + ", timedOut=" + timedOut + ", failed=" + failed + ", drainedFailed=" + drainedFailed
                + ", maxFlushNanos=" + maxFlushNanos + "]";
    }
}
