package com.betterschool.integration.timeout;

import com.betterschool.infrastructure.timeout.ScanSummary;
import com.betterschool.infrastructure.timeout.TaskKey;
import com.betterschool.trigger.job.TimeoutTaskScanJob;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时中心内嵌化票 04 的扫描闭环验收：认领槽位 → 捞批到空 → 逐项回调 → 按结果分桶结算。
 * <p>
 * 单 seam = {@code TimeoutScanner}（xxl-job 的壳子只负责取分片参数，另有用例单独验它）；
 * 回调边用探针**控制结果**——探针作为入参递给扫描器，生产递 {@code trigger.job.TimeoutActionRouter}；
 * 真装配落在业务动作上，这里钉的是「扫描/结算这台机器」。
 * <p>
 * 断言的另一半是物理分表：状态、重试计数、下次触发时刻都直连物理表看（绕开 ShardingSphere）。
 */
@DisplayName("票 04：扫描 tick 的认领/捞批/回调/结算闭环")
class TimeoutTaskScanIntegrationTest extends TimeoutScanTestBase {

    @Autowired
    private TimeoutTaskScanJob timeoutTaskScanJob;

    @Value("${timeout.scan.batch-size}")
    private int batchSize;

    @Value("${timeout.retry.base-seconds}")
    private long retryBaseSeconds;

    @Value("${timeout.retry.max-times}")
    private int retryMaxTimes;

    @Test
    void 到期的任务被回调一次并置成功终态() {
        String bizId = "scan-success-1";
        registerDue(bizId, -1);
        callbackProbe.stubSuccess();

        ScanSummary summary = scan(slotOf(bizId), TOTAL_SLOTS);

        assertThat(summary.getSlotCount()).isEqualTo(1);
        assertThat(summary.getBatchCount()).isEqualTo(1);
        assertThat(summary.getTaskCount()).isEqualTo(1);
        assertThat(summary.getSuccessCount()).isEqualTo(1);
        assertThat(callbackProbe.invocationCountOf(TASK_TYPE, bizId)).isEqualTo(1);
        Map<String, Object> row = physicalRow(bizId);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("retry_count")).isEqualTo(0);
    }

    @Test
    void 回调失败进重试并退避三十秒_同一tick内不再被重捞() {
        String bizId = "scan-retry-1";
        registerDue(bizId, -1);
        callbackProbe.stubFailure();
        LocalDateTime beforeScan = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        ScanSummary summary = scan(slotOf(bizId), TOTAL_SLOTS);

        assertThat(summary.getRetryCount()).isEqualTo(1);
        assertThat(summary.getSuccessCount()).isZero();
        // 「循环拉批到空」的终止条件：退避把行推到 dueBefore 之后，同一 tick 的下一轮 fetch 捞不到它
        assertThat(callbackProbe.invocationCountOf(TASK_TYPE, bizId))
                .as("同一 tick 内被重复回调 = dueBefore 每批现取，退避没挡住重捞")
                .isEqualTo(1);

        Map<String, Object> row = physicalRow(bizId);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(row.get("retry_count")).isEqualTo(1);
        assertThat(dateTime(row.get("next_fire_time")))
                .as("首次失败 → 退避 %ds", retryBaseSeconds)
                .isAfterOrEqualTo(beforeScan.plusSeconds(retryBaseSeconds))
                .isBefore(beforeScan.plusSeconds(retryBaseSeconds + 5));

        // 紧接着再扫一轮：退避时刻还没到，不该再回调
        scan(slotOf(bizId), TOTAL_SLOTS);
        assertThat(callbackProbe.invocationCountOf(TASK_TYPE, bizId)).isEqualTo(1);
        assertThat(physicalRow(bizId).get("retry_count")).isEqualTo(1);
    }

    @Test
    void 第16次失败置FAILED死信且离开扫描集合() {
        String bizId = "scan-dead-1";
        registerDue(bizId, -1);
        // 等它自然失败 16 次要 10 小时：把「已失败次数」摆到上限前一次，本次失败即进死信
        setRetryCount(TASK_TYPE, bizId, retryMaxTimes - 1);
        callbackProbe.stubFailure();

        ScanSummary summary = scan(slotOf(bizId), TOTAL_SLOTS);

        assertThat(summary.getDeadCount()).isEqualTo(1);
        assertThat(summary.getRetryCount()).isZero();
        Map<String, Object> row = physicalRow(bizId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        // 第 16 次失败照样记账（retry_count 数的是「失败过几次」，不是「还会重试几次」）
        assertThat(row.get("retry_count")).isEqualTo(retryMaxTimes);

        // 终态行离开扫描集合：再扫一轮不回调、行也不动
        int invocationsBefore = callbackProbe.invocationCount();
        ScanSummary second = scan(slotOf(bizId), TOTAL_SLOTS);
        assertThat(second.getTaskCount()).isZero();
        assertThat(callbackProbe.invocationCount()).isEqualTo(invocationsBefore);
        assertThat(physicalRow(bizId).get("retry_count")).isEqualTo(retryMaxTimes);
    }

    @Test
    void 未到点与已取消的行都不被捞起() {
        registerDue("scan-future-1", 15);
        registerDue("scan-canceled-1", -1);
        assertThat(timeoutTaskStore.cancelActive(TASK_TYPE, "scan-canceled-1")).isTrue();
        callbackProbe.stubSuccess();

        // 单实例（shardTotal = 1）认领全部 16 个槽位
        ScanSummary summary = scan(0, 1);

        assertThat(summary.getSlotCount()).isEqualTo(TOTAL_SLOTS);
        assertThat(summary.getTaskCount()).isZero();
        assertThat(callbackProbe.invocationCount()).isZero();
        assertThat(physicalRow("scan-future-1").get("status")).isEqualTo("WAITING");
        assertThat(physicalRow("scan-canceled-1").get("status")).isEqualTo("CANCELED");
    }

    @Test
    void 单批拉不完时循环拉批到空_无单tick批数上限() {
        int slot = 3;
        List<String> bizIds = bizIdsForSlot("scan-bulk-", slot, batchSize + 5);
        bizIds.forEach(bizId -> registerDue(bizId, -1));
        callbackProbe.stubSuccess();

        ScanSummary summary = scan(slot, TOTAL_SLOTS);

        assertThat(summary.getBatchCount()).as("单批上限 %d：%d 条 = 两批", batchSize, batchSize + 5).isEqualTo(2);
        assertThat(summary.getTaskCount()).isEqualTo(batchSize + 5);
        assertThat(summary.getSuccessCount()).isEqualTo(batchSize + 5);
        assertThat(callbackProbe.invocationCount()).isEqualTo(batchSize + 5);
        assertThat(physicalRow(bizIds.get(0)).get("status")).isEqualTo("SUCCESS");
        assertThat(physicalRow(bizIds.get(bizIds.size() - 1)).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void 非本实例槽位的任务既不被回调也不被结算() {
        String bizId = bizIdForSlot("scan-own-", 5);
        registerDue(bizId, -1);
        int otherSlot = (slotOf(bizId) + 1) % TOTAL_SLOTS;
        callbackProbe.stubSuccess();

        ScanSummary otherSummary = scan(otherSlot, TOTAL_SLOTS);
        assertThat(callbackProbe.invocationCount()).isZero();
        assertThat(otherSummary.getTaskCount()).isZero();
        assertThat(physicalRow(bizId).get("status")).isEqualTo("WAITING");

        // 轮到它自己的槽位才回调（hint 强制路由精确落到那一张分表）
        scan(slotOf(bizId), TOTAL_SLOTS);
        assertThat(callbackProbe.invocationCountOf(TASK_TYPE, bizId)).isEqualTo(1);
        assertThat(physicalRow(bizId).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void 多实例下每实例只认领自己的槽位() {
        // 16 个槽位各摆一行，实例 1/3 应该只吃槽位 1、4、7、10、13
        List<String> bizIds = new ArrayList<>();
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            String bizId = bizIdForSlot("scan-shard-" + slot + "-", slot);
            registerDue(bizId, -1);
            bizIds.add(bizId);
        }
        callbackProbe.stubSuccess();

        ScanSummary summary = scan(1, 3);

        assertThat(summary.getSlotCount()).isEqualTo(5);
        assertThat(summary.getTaskCount()).isEqualTo(5);
        List<String> expectedBizIds = IntStream.range(0, TOTAL_SLOTS)
                .filter(slot -> slot % 3 == 1)
                .mapToObj(bizIds::get)
                .toList();
        assertThat(callbackProbe.invocations()).extracting(TaskKey::getBizId)
                .containsExactlyInAnyOrderElementsOf(expectedBizIds);
        // 别人的槽位一行没动（没被结算、更没被回调）
        assertThat(physicalRow(bizIds.get(0)).get("status")).isEqualTo("WAITING");
        assertThat(physicalRow(bizIds.get(1)).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void 两个实例各认领一半槽位_合计覆盖16张表且无重复回调() {
        // 16 个槽位各摆一行；实例 0/2 吃偶数槽、实例 1/2 吃奇数槽，两次 scan 拼起来应恰好覆盖全部
        List<String> bizIds = new ArrayList<>();
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            String bizId = bizIdForSlot("scan-half-" + slot + "-", slot);
            registerDue(bizId, -1);
            bizIds.add(bizId);
        }
        callbackProbe.stubSuccess();

        ScanSummary first = scan(0, 2);
        ScanSummary second = scan(1, 2);

        assertThat(first.getSlotCount()).isEqualTo(TOTAL_SLOTS / 2);
        assertThat(second.getSlotCount()).isEqualTo(TOTAL_SLOTS / 2);
        assertThat(first.getTaskCount() + second.getTaskCount())
                .as("两次扫描合计应覆盖 16 张分表各一行")
                .isEqualTo(TOTAL_SLOTS);
        // 每个槽位只会被一个实例认领：回调总数恰 16 条且 bizId 无重复
        assertThat(callbackProbe.invocations())
                .as("多实例认领重叠 = 同一任务被回调两次")
                .hasSize(TOTAL_SLOTS)
                .extracting(TaskKey::getBizId)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(bizIds);
        for (String bizId : bizIds) {
            assertThat(physicalRow(bizId).get("status")).isEqualTo("SUCCESS");
        }
    }

    @Test
    void 批内一项失败不拖垮同批其余项() {
        int slot = 6;
        String good = bizIdForSlot("scan-iso-good-", slot);
        String bad = bizIdForSlot("scan-iso-bad-", slot);
        registerDue(good, -1);
        registerDue(bad, -1);
        callbackProbe.failOnly(TASK_TYPE, bad);

        ScanSummary summary = scan(slot, TOTAL_SLOTS);

        assertThat(summary.getSuccessCount()).isEqualTo(1);
        assertThat(summary.getRetryCount()).isEqualTo(1);
        assertThat(physicalRow(good).get("status")).isEqualTo("SUCCESS");
        assertThat(physicalRow(bad).get("status")).isEqualTo("RETRYING");
    }

    @Test
    void 捞批与结算之间被取消的行不被回调结果翻回来() {
        String bizId = "scan-race-cancel-1";
        registerDue(bizId, -1);
        // 探针在回调途中（= 捞批与结算之间的窗口）把这一行取消掉，再回报成功
        callbackProbe.cancelOnCallback(TASK_TYPE, bizId);

        ScanSummary summary = scan(slotOf(bizId), TOTAL_SLOTS);

        // 账本按回报记账（这一项确实回报了成功），但库上的行级守卫说了算
        assertThat(summary.getSuccessCount()).isEqualTo(1);
        Map<String, Object> row = physicalRow(bizId);
        assertThat(row.get("status"))
                .as("结算的三条 UPDATE 都带 status IN ('WAITING','RETRYING')，取消不该被翻回 SUCCESS")
                .isEqualTo("CANCELED");
        assertThat(row.get("retry_count")).isEqualTo(0);
    }

    @Test
    void 死信重发只打FAILED行_且不动action_time() {
        String dead = "dead-resend-1";
        makeDeadLetter(dead);
        LocalDateTime actionTimeBefore = dateTime(physicalRow(dead).get("action_time"));
        LocalDateTime beforeResend = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        assertThat(timeoutTaskStore.resendDeadLetter(TASK_TYPE, dead)).isTrue();

        Map<String, Object> row = physicalRow(dead);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(row.get("retry_count")).as("重发 = 重开一轮，计数清零").isEqualTo(0);
        assertThat(dateTime(row.get("next_fire_time"))).as("重发后立刻可被下一个扫描周期捞起")
                .isAfterOrEqualTo(beforeResend).isBefore(beforeResend.plusSeconds(5));
        assertThat(dateTime(row.get("action_time"))).as("理论到期时刻不可变").isEqualTo(actionTimeBefore);

        // 未终态行不是死信：重发对它无副作用（条件 UPDATE 只打 FAILED）
        String waiting = "waiting-resend-1";
        registerDue(waiting, 15);
        LocalDateTime nextFireBefore = dateTime(physicalRow(waiting).get("next_fire_time"));
        assertThat(timeoutTaskStore.resendDeadLetter(TASK_TYPE, waiting)).isFalse();
        Map<String, Object> untouched = physicalRow(waiting);
        assertThat(untouched.get("status")).isEqualTo("WAITING");
        assertThat(untouched.get("retry_count")).isEqualTo(0);
        assertThat(dateTime(untouched.get("next_fire_time"))).isEqualTo(nextFireBefore);
    }

    @Test
    void 死信取消只打FAILED行() {
        String dead = "dead-cancel-1";
        makeDeadLetter(dead);

        assertThat(timeoutTaskStore.cancelDeadLetter(TASK_TYPE, dead)).isTrue();
        assertThat(physicalRow(dead).get("status")).isEqualTo("CANCELED");

        // 未终态行（在途）不走死信取消这条路：业务取消是另一条（cancelActive）
        String waiting = "waiting-cancel-1";
        registerDue(waiting, 15);
        assertThat(timeoutTaskStore.cancelDeadLetter(TASK_TYPE, waiting)).isFalse();
        assertThat(physicalRow(waiting).get("status")).isEqualTo("WAITING");
    }

    @Test
    void 扫描job按分片广播参数只扫本分片并回报本轮账() {
        String bizId = bizIdForSlot("scan-job-", 4);
        registerDue(bizId, -1);
        callbackProbe.stubSuccess();
        XxlJobContext context = new XxlJobContext(1L, "", "test-log", slotOf(bizId), TOTAL_SLOTS);
        XxlJobContext.setXxlJobContext(context);
        try {
            timeoutTaskScanJob.scanJob();

            assertThat(callbackProbe.invocationCountOf(TASK_TYPE, bizId)).isEqualTo(1);
            assertThat(context.getHandleCode()).isEqualTo(XxlJobContext.HANDLE_CODE_SUCCESS);
            assertThat(context.getHandleMsg()).contains("扫描完成").contains("成功 1");
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    /** 把一行做成死信（FAILED）：注册到点 → 摆到上限前一次 → 回调按失败 → 扫一轮 */
    private void makeDeadLetter(String bizId) {
        registerDue(bizId, -1);
        setRetryCount(TASK_TYPE, bizId, retryMaxTimes - 1);
        callbackProbe.stubFailure();
        ScanSummary summary = scan(slotOf(bizId), TOTAL_SLOTS);
        assertThat(summary.getDeadCount()).as("前置条件：%s 应进死信", bizId).isEqualTo(1);
        assertThat(physicalRow(bizId).get("status")).isEqualTo("FAILED");
    }
}
