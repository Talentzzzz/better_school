package com.betterschool.integration.timeout;

import com.betterschool.infrastructure.timeout.ScanSummary;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时中心内嵌化票 04 的并行度验收：**认领 N 张表 ≠ 开 N 条线程 / 占 N 条连接**。
 * <p>
 * 为什么这条是本票的实质工作：独立中心敢「一表一虚拟线程」，是因为它有专属的池 24（1.5 × 槽位数）；
 * 单体 Hikari 池只有 10 且与用户请求共用，照搬会在 16 张表同时抢连接时把池占满，用户请求随即
 * {@code Connection is not available}。所以认领 16 张表时实际并发必须 ≤ {@code timeout.scan.parallelism}。
 * <p>
 * 两侧证据都取（票面允许「Hikari 指标或探针」）：
 * <ul>
 *   <li><b>探针（因果侧）</b>：回调闩要求凑满 {@code parallelism} 个同时在跑的回调才放行——凑不满
 *       = 根本没并行（退化成串行）；同时记下回调时刻的并发峰值与跑过回调的线程名。</li>
 *   <li><b>Hikari 指标（观测侧）</b>：扫描期间采样式记录活跃连接峰值——它是「并发上限 → 连接占用」
 *       这条链的末端观测，断言 ≤ 并行度（否则用户请求的余量就没了）。</li>
 * </ul>
 */
@DisplayName("票 04：有界并行（表数与并发上限解耦）")
class TimeoutTaskScanParallelismIntegrationTest extends TimeoutScanTestBase {

    @Autowired
    private HikariDataSource dataSource;

    @Value("${timeout.scan.parallelism}")
    private int parallelism;

    @Test
    void 单实例认领16张分表时并发不超过并行度() {
        List<String> bizIds = registerOneDueTaskPerSlot("scan-par-all-");

        ScanSummary scanSummary = scanWithConcurrencyProbe(bizIds, 0, 1);

        assertThat(scanSummary.getSlotCount()).isEqualTo(TOTAL_SLOTS);
        assertThat(scanSummary.getTaskCount()).isEqualTo(TOTAL_SLOTS);
        assertThat(scanSummary.getSuccessCount()).isEqualTo(TOTAL_SLOTS);
        // 16 张表的行全部结算完：并行度只限并发，不限吞吐（其余在池队列里排队）
        assertThat(physicalRow(bizIds.get(0)).get("status")).isEqualTo("SUCCESS");
        assertThat(physicalRow(bizIds.get(TOTAL_SLOTS - 1)).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void 多实例认领8张分表时并发同样不超过并行度() {
        List<String> bizIds = registerOneDueTaskPerSlot("scan-par-half-");

        // shardIndex = 0、shardTotal = 2：本实例认领 8 张表（偶数槽位）
        ScanSummary scanSummary = scanWithConcurrencyProbe(bizIds, 0, 2);

        assertThat(scanSummary.getSlotCount()).isEqualTo(TOTAL_SLOTS / 2);
        assertThat(scanSummary.getTaskCount()).isEqualTo(TOTAL_SLOTS / 2);
        assertThat(scanSummary.getSuccessCount()).isEqualTo(TOTAL_SLOTS / 2);
    }

    // ---------- 辅助 ----------

    /** 16 个槽位各注册一行到期任务（bizId 精确落在槽位上），返回按槽位序的 bizId */
    private List<String> registerOneDueTaskPerSlot(String prefix) {
        List<String> bizIds = new ArrayList<>(TOTAL_SLOTS);
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            String bizId = bizIdForSlot(prefix + slot + "-", slot);
            registerDue(bizId, -1);
            bizIds.add(bizId);
        }
        return bizIds;
    }

    /** 摆探针（闩 + 观测）跑一轮扫描，并断言并行度的三条证据；返回本轮账 */
    private ScanSummary scanWithConcurrencyProbe(List<String> bizIds, int shardIndex, int shardTotal) {
        callbackProbe.stubSuccess();
        // 池宽度 = min(并行度, 本实例认领到的表数)：两个档（16 张 / 8 张）都断言这一条
        int expectedConcurrency = Math.max(1, Math.min(parallelism, claimedSlotCount(shardIndex, shardTotal)));
        callbackProbe.armConcurrencyBarrier(expectedConcurrency);

        ScanSummary[] summary = new ScanSummary[1];
        int hikariPeak = runWithHikariPeakSampling(() -> summary[0] = scan(shardIndex, shardTotal));

        assertThat(callbackProbe.barrierTimeouts())
                .as("回调没凑满 %d 个并发 = 没并行起来（或池宽度小于并行度）", expectedConcurrency)
                .isZero();
        assertThat(callbackProbe.barrierTrips()).isPositive();
        assertThat(callbackProbe.maxInFlight())
                .as("回调时刻的并发项数 = 池宽度，必须恰好是并行度（认领的表数不该放大它）")
                .isEqualTo(expectedConcurrency);
        assertThat(callbackProbe.callbackThreadNames())
                .as("跑过回调的扫描线程数（固定池命名 timeout-scan-N）：绝不「一表一线程」")
                .hasSizeLessThanOrEqualTo(expectedConcurrency);
        assertThat(hikariPeak)
                .as("扫描期 Hikari 活跃连接峰值：池 10 要留给用户请求，扫描占用不得超并行度")
                .isLessThanOrEqualTo(expectedConcurrency);
        return summary[0];
    }

    /** 认领条件同用例层：{@code g % shardTotal == shardIndex} */
    private static int claimedSlotCount(int shardIndex, int shardTotal) {
        int count = 0;
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            if (slot % shardTotal == shardIndex) {
                count++;
            }
        }
        return count;
    }

    /** 跑动作的同时用一条采样线程记录 Hikari 活跃连接峰值（2ms 一次，够密于语句级占用） */
    private int runWithHikariPeakSampling(Runnable action) {
        AtomicInteger peak = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().daemon(true).name("hikari-peak-sampler").start(() -> {
            while (sampling.get()) {
                HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
                if (pool != null) {
                    peak.accumulateAndGet(pool.getActiveConnections(), Math::max);
                }
                LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
            }
        });
        try {
            action.run();
        } finally {
            sampling.set(false);
            sampler.interrupt();
        }
        return peak.get();
    }
}
