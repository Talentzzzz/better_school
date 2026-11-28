package com.betterschool.unit.timeout;

import com.betterschool.infrastructure.timeout.CallbackReport;
import com.betterschool.infrastructure.timeout.ITimeoutBucketWriter;
import com.betterschool.infrastructure.timeout.SettleOutcome;
import com.betterschool.infrastructure.timeout.TaskKey;
import com.betterschool.infrastructure.timeout.TimeoutSettler;
import com.betterschool.infrastructure.timeout.TimeoutTaskEntity;
import com.betterschool.infrastructure.timeout.TimeoutTaskStatusEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算分桶与退避公式的离线单测：这一层要钉死的是<b>「每批每桶一次写」这条形状</b>与
 * <b>退避是按行算的</b>——批量语句本身（CASE/IN/hint 路由）由 app 模块的集成测试在真库上验，
 * 两者不重叠。
 * <p>
 * 不连栈：分桶是纯函数式的，用一个假写出面（{@link ITimeoutBucketWriter}，3 个方法）
 * 数「被调用了几次、每次带多少行」。集成测试里要数 SQL 才看得出来的账（200 条 → 2 次写）在这里一眼可见。
 */
@DisplayName("结算分桶与退避公式（离线）")
class TimeoutSettlerTest {

    private static final String TASK_TYPE = "PRODUCT_ORDER_PAY_TIMEOUT";
    private static final String TASK_TYPE_SECOND = "PRODUCT_ORDER_REFUND_TIMEOUT";
    private static final int SLOT = 7;
    /** 退避底数 / 封顶 / 上限：与 application(-test).yml 的 timeout.retry.* 同值 */
    private static final long BASE_SECONDS = 30;
    private static final long MAX_SECONDS = 3600;
    private static final int MAX_TIMES = 16;

    private final FakeWriter writer = new FakeWriter();
    private final TimeoutSettler settler = settler();

    @Test
    void 满批两百条只发两次写_而不是逐行一次() {
        List<TimeoutTaskEntity> batch = new ArrayList<>();
        List<TaskKey> successKeys = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String bizId = "order-" + i;
            batch.add(task(bizId, 0));
            // 结果按 3:1 铺开：成功桶 150、重试桶 50，两桶都不止一行
            if (i % 4 != 0) {
                successKeys.add(TaskKey.of(TASK_TYPE, bizId));
            }
        }

        SettleOutcome settleOutcome = settler.settle(batch, CallbackReport.of(Set.copyOf(successKeys)));

        assertThat(settleOutcome.getSuccessCount()).isEqualTo(150);
        assertThat(settleOutcome.getRetryCount()).isEqualTo(50);
        assertThat(settleOutcome.getDeadCount()).isZero();
        // 关键断言：一次调用 = 一个非空桶一次写。逐行实现下这个数会是 200。
        assertThat(writer.writeCalls).isEqualTo(2);
        assertThat(writer.successBatchSizes).containsExactly(150);
        assertThat(writer.retryBatchSizes).containsExactly(50);
        // 死信桶是空的：不该发语句（`IN ()` 语法错误，且有「按空桶算一次写」的运行成本）
        assertThat(writer.deadBatchSizes).isEmpty();
    }

    @Test
    void 死信桶与重试桶各自合成一次写_重复的退避时刻走同一分支() {
        List<TimeoutTaskEntity> batch = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            // 一半是首次失败（retry_count=0 ⇒ 退避 30s），一半已失败 15 次（本次是第 16 次 ⇒ 进死信）
            batch.add(task("order-" + i, i % 2 == 0 ? 0 : 15));
        }

        SettleOutcome settleOutcome = settler.settle(batch, CallbackReport.none());

        assertThat(settleOutcome.getRetryCount()).isEqualTo(100);
        assertThat(settleOutcome.getDeadCount()).isEqualTo(100);
        assertThat(writer.writeCalls).isEqualTo(2);
        assertThat(writer.deadBatchSizes).containsExactly(100);
        assertThat(writer.retryBatchSizes).containsExactly(100);
        // 重试桶里 100 行、退避秒数只有 1 档 ⇒ SQL 的 CASE 就一个分支，不随行数长
        assertThat(writer.lastBackoffSeconds).hasSize(100);
        assertThat(writer.lastBackoffSeconds.values().stream().distinct()).hasSize(1);
    }

    @Test
    void 同一批里两种任务类型各写各的桶_空桶不发语句() {
        List<TimeoutTaskEntity> batch = List.of(
                task("order-1", 0),
                task("order-2", 0),
                task(TASK_TYPE_SECOND, "biz-1", 0));

        // 只有前两行回调成功；另一任务类型的项没有回报（按失败进重试）
        SettleOutcome settleOutcome = settler.settle(batch, CallbackReport.of(Set.of(
                TaskKey.of(TASK_TYPE, "order-1"), TaskKey.of(TASK_TYPE, "order-2"))));

        assertThat(settleOutcome.getSuccessCount()).isEqualTo(2);
        assertThat(settleOutcome.getRetryCount()).isEqualTo(1);
        // 两个 taskType 各一个非空桶 = 两次写（各 taskType 的另外两个桶都空，不发）
        assertThat(writer.writeCalls).isEqualTo(2);
        assertThat(writer.successTaskTypes).containsExactly(TASK_TYPE);
        assertThat(writer.retryTaskTypes).containsExactly(TASK_TYPE_SECOND);
    }

    @Test
    void 退避按指数翻倍递进且单次封顶一小时() {
        // 行上的 retry_count = 已失败次数：本次是第 N+1 次失败，间隔取自 30 × 2^N
        assertNextRetryDelay(0, BASE_SECONDS);
        assertNextRetryDelay(1, BASE_SECONDS * 2);
        assertNextRetryDelay(3, BASE_SECONDS * 8);
        // 底数翻过封顶：配置的封顶值说了算（30 × 2^9 = 15360s → 3600s）
        assertNextRetryDelay(9, MAX_SECONDS);
    }

    @Test
    void 退避按行算_同桶内两行不同退避互不串序() {
        // 一个批里两行都失败、retry_count 不同 ⇒ 同一条 CASE 语句里两个分支各取各的退避秒数。
        // 键序错位（bizIds 与 backoffSeconds 不对齐）会在这里露出来：断言「每个 bizId 拿到自己那一档」
        //（Java 侧只算秒数，绝对时刻由 SQL 侧 TIMESTAMPADD 按数据库时钟换算，故这里断精确秒值）
        List<TimeoutTaskEntity> batch = List.of(task("order-first", 0), task("order-second", 1));

        settler.settle(batch, CallbackReport.none());

        LinkedHashMap<String, Integer> backoffSeconds = writer.lastBackoffSeconds;
        assertThat(backoffSeconds.keySet()).containsExactly("order-first", "order-second");
        assertThat(backoffSeconds.get("order-first")).isEqualTo((int) BASE_SECONDS);
        assertThat(backoffSeconds.get("order-second")).isEqualTo((int) (BASE_SECONDS * 2));
    }

    @Test
    void 退避封顶配成零也要落到未来_别让同一tick把重试跑光() {
        TimeoutSettler zeroCap = settler();
        // 配置事故（封顶写成 0）：退避若算成 0，next_fire_time 贴住落库时刻，
        // 同一轮「循环拉批到空」会把这一行反复捞起来，一次 tick 就跑光 16 次重试
        setField(zeroCap, "retryMaxSeconds", 0L);

        zeroCap.settle(List.of(task("order-zero-cap", 0)), CallbackReport.none());

        assertThat(writer.lastBackoffSeconds.get("order-zero-cap"))
                .as("退避秒数必须 ≥1（封顶兜底 1s），否则本轮就会被重捞")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void 批上没带槽位时立刻报错_不留到没塞hint的批量语句上() {
        TimeoutTaskEntity timeoutTaskEntity = task("order-1", 0);
        timeoutTaskEntity.setShardSlot(null);

        assertThatThrownBy(() -> settler.settle(List.of(timeoutTaskEntity), CallbackReport.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shardSlot");
    }

    @Test
    void 空批不产生任何写() {
        assertThat(settler.settle(List.of(), CallbackReport.none()).getSuccessCount()).isZero();
        assertThat(writer.writeCalls).isZero();
    }

    // ---------- 辅助 ----------

    /** 摆一行「已失败 {@code retryCount} 次」的失败任务，结算后断言传给写出面的退避秒数 = 精确期望值 */
    private void assertNextRetryDelay(int retryCount, long expectedDelaySeconds) {
        settler.settle(List.of(task("order-backoff-" + retryCount, retryCount)), CallbackReport.none());

        // Java 侧只算秒数（绝对时刻由 SQL 侧按数据库时钟换算），这里断精确值
        assertThat(writer.lastBackoffSeconds.get("order-backoff-" + retryCount))
                .as("retry_count=%d 时的退避间隔应为 %ds", retryCount, expectedDelaySeconds)
                .isEqualTo((int) expectedDelaySeconds);
    }

    private static TimeoutTaskEntity task(String bizId, int retryCount) {
        return task(TASK_TYPE, bizId, retryCount);
    }

    private static TimeoutTaskEntity task(String taskType, String bizId, int retryCount) {
        TimeoutTaskEntity timeoutTaskEntity = TimeoutTaskEntity.newWaiting(taskType, bizId, 60L);
        timeoutTaskEntity.setStatus(TimeoutTaskStatusEnum.RETRYING);
        timeoutTaskEntity.setRetryCount(retryCount);
        timeoutTaskEntity.setShardSlot(SLOT);
        return timeoutTaskEntity;
    }

    /** 退避与死信上限取 application(-test).yml 的口径，这里显式摆进结算器（组件用 @Value 默认值兜底） */
    private TimeoutSettler settler() {
        TimeoutSettler timeoutSettler = new TimeoutSettler(writer);
        setField(timeoutSettler, "retryBaseSeconds", BASE_SECONDS);
        setField(timeoutSettler, "retryMaxSeconds", MAX_SECONDS);
        setField(timeoutSettler, "retryMaxTimes", MAX_TIMES);
        return timeoutSettler;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("摆测试字段失败：" + name, e);
        }
    }

    /**
     * 只记账的假写出面：写方法不碰库，只记「被调了几次、每次带多少行」——这正是「批量化了没」的证据。
     * <p>
     * 空桶直接返回、不算一次写：真实现也是这个口径（空列表直接 return，`IN ()` 是语法错误）。
     * 三个方法就是 {@link ITimeoutBucketWriter} 的全部——这条窄缝就是为了这个假件存在的。
     */
    private static final class FakeWriter implements ITimeoutBucketWriter {

        private int writeCalls;
        private final List<Integer> successBatchSizes = new ArrayList<>();
        private final List<Integer> retryBatchSizes = new ArrayList<>();
        private final List<Integer> deadBatchSizes = new ArrayList<>();
        private final List<String> successTaskTypes = new ArrayList<>();
        private final List<String> retryTaskTypes = new ArrayList<>();
        private LinkedHashMap<String, Integer> lastBackoffSeconds = new LinkedHashMap<>();

        @Override
        public int markSuccessBatch(int slot, String taskType, List<String> bizIds) {
            if (bizIds.isEmpty()) {
                return 0;
            }
            writeCalls++;
            successBatchSizes.add(bizIds.size());
            successTaskTypes.add(taskType);
            return bizIds.size();
        }

        @Override
        public int markRetryBatch(int slot, String taskType, LinkedHashMap<String, Integer> backoffSeconds) {
            if (backoffSeconds.isEmpty()) {
                return 0;
            }
            writeCalls++;
            retryBatchSizes.add(backoffSeconds.size());
            retryTaskTypes.add(taskType);
            lastBackoffSeconds = backoffSeconds;
            return backoffSeconds.size();
        }

        @Override
        public int markDeadBatch(int slot, String taskType, List<String> bizIds) {
            if (bizIds.isEmpty()) {
                return 0;
            }
            writeCalls++;
            deadBatchSizes.add(bizIds.size());
            return bizIds.size();
        }
    }
}
