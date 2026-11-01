package com.betterschool.domain.timeout.service.scan;

import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.domain.timeout.model.valobj.CallbackReportVO;
import com.betterschool.domain.timeout.model.valobj.SettleOutcomeVO;
import com.betterschool.domain.timeout.model.valobj.TaskKeyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 / 结算领域服务实现（超时中心内嵌化票 04）。
 * <p>
 * 结算就是把回调结果翻成**每批每桶一条**条件 UPDATE：成功置终态、失败进重试或（到上限了）进死信。
 * 裁决权仍在库上（三条 UPDATE 都只打未终态行），所以「回调在途被取消」这类竞态不会把 CANCELED 翻回来。
 * <p>
 * <b>不用 {@code INTERVAL ?}</b>（票 02 实测的 SQL 形态避让）：datasource 已坐在 ShardingSphere 驱动上，
 * 「函数内嵌占位符」会被解析器弄丢参数，所以退避时刻在域服务里算成普通 {@code LocalDateTime} 参数传下去。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutTaskScanService implements ITimeoutTaskScanService {

    /** 移位前的指数上限（防 long 溢出）：base=30s 时 2^20 已远超封顶值，再多算没意义 */
    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final ITimeoutRepository timeoutRepository;

    /** 退避底数：首次失败后 30s */
    @Value("${timeout.retry.base-seconds:30}")
    private long retryBaseSeconds;

    /** 单次退避封顶（1h，可配） */
    @Value("${timeout.retry.max-seconds:3600}")
    private long retryMaxSeconds;

    /** 重试次数上限（16 次，可配）；打满即进 FAILED 死信等人工处理 */
    @Value("${timeout.retry.max-times:16}")
    private int retryMaxTimes;

    @Override
    public List<TimeoutTaskEntity> fetchDueBatch(int slot, LocalDateTime dueBefore, int limit) {
        return timeoutRepository.selectDueBatch(slot, dueBefore, limit);
    }

    /**
     * 结算一批：先把整批按「成功 / 死信 / 重试」分桶，再**每桶一条批量条件 UPDATE**。
     * <p>
     * <b>为什么可以一条语句覆盖整批</b>：一个批是 {@link #fetchDueBatch 按槽位}捞出来的，
     * 本来就在同一张物理分表上（捞批前已塞好 hint），所以不存在跨库跨表语句。写库次数因此与
     * 「批内结果种类数 × taskType 数」成正比，不再与批大小成正比。
     * <p>
     * <b>没变的</b>：三条 SQL 都带 {@code status IN ('WAITING','RETRYING')}，裁决权仍在库上，
     * 所以「回调在途被取消」这类竞态不会被回调结果翻转；返回值不再是逐行布尔，而是每桶实际改到的行数。
     */
    @Override
    public SettleOutcomeVO settle(List<TimeoutTaskEntity> batch, CallbackReportVO report) {
        if (batch.isEmpty()) {
            return new SettleOutcomeVO(0, 0, 0);
        }
        int slot = slotOfBatch(batch);
        Map<String, BucketIds> byTaskType = partition(batch, report);

        int successCount = 0;
        int retryCount = 0;
        int deadCount = 0;
        LocalDateTime earliestNextFireTime = null;
        for (Map.Entry<String, BucketIds> entry : byTaskType.entrySet()) {
            String taskType = entry.getKey();
            BucketIds buckets = entry.getValue();

            successCount += buckets.successIds.size();
            deadCount += buckets.deadIds.size();
            retryCount += buckets.retryTimes.size();
            earliestNextFireTime = earliestOf(earliestNextFireTime, buckets.retryTimes.values());

            // 死信逐行报：一行只会死一次，低频且每条都值得单独可查（要人工处理的就它）
            for (String bizId : buckets.deadIds) {
                log.error("[超时中心告警] 重试 {} 次仍失败，进死信（FAILED）等人工处理：taskType={}, bizId={}",
                        retryLimit(), taskType, bizId);
            }
            markBuckets(slot, taskType, buckets);
        }
        // 重试是**热路径**（每次回调失败都走它），逐行 WARN 在「业务方整体不可用」时会一个 tick 刷出
        // 几千行、把真正的告警淹掉——按批汇总一条，行级细节本来就在库里查得到。
        if (retryCount > 0) {
            log.warn("[超时中心告警] 本批 {} 条回调失败进入重试（最早一次重投 {}）", retryCount, earliestNextFireTime);
        }
        return new SettleOutcomeVO(successCount, retryCount, deadCount);
    }

    /**
     * 按「回调结果 × taskType」分桶（taskType 是批量 UPDATE 里的常量条件，所以按它再切一层；
     * 单批通常只有一个 taskType——它是自然键的一半，同一业务对象的多种超时是不同类型）。
     * <p>
     * 判据顺序就是既有的优先级：成功（{@link CallbackReportVO#isSucceeded}）→ 失败且已达重试上限（死信）
     * → 其余按重试。批内每一项因此恰好落进一个桶。
     */
    private Map<String, BucketIds> partition(List<TimeoutTaskEntity> batch, CallbackReportVO report) {
        Map<String, BucketIds> byTaskType = new LinkedHashMap<>();
        for (TimeoutTaskEntity task : batch) {
            BucketIds buckets = byTaskType.computeIfAbsent(task.getTaskType(), key -> new BucketIds());
            if (report.isSucceeded(TaskKeyVO.of(task.getTaskType(), task.getBizId()))) {
                buckets.successIds.add(task.getBizId());
            } else if (reachesRetryLimit(task)) {
                buckets.deadIds.add(task.getBizId());
            } else {
                buckets.retryTimes.put(task.getBizId(), nextFireTimeAfterFailure(task));
            }
        }
        return byTaskType;
    }

    /**
     * 每桶一次写，空桶**不发语句**（`IN ()` 是语法错误；即便仓储层挡住了，也不该白跑一趟调用）。
     * <p>
     * 三桶互斥，且都带未终态条件，所以「同键被两条语句各打一次」也不存在。
     * 返回值（实际改到的行数）只记不理：与入参条数的差值 = 已被取消/已终态的行——那是设计内的竞态，
     * 不是错误，账本口径仍是「按回报归到哪一桶」（{@link SettleOutcomeVO}）。
     */
    private void markBuckets(int slot, String taskType, BucketIds buckets) {
        if (buckets.successIds.isEmpty() && buckets.retryTimes.isEmpty() && buckets.deadIds.isEmpty()) {
            return;
        }
        int succeeded = timeoutRepository.markSuccessBatch(slot, taskType, buckets.successIds);
        int retried = timeoutRepository.markRetryBatch(slot, taskType, buckets.retryTimes);
        int dead = timeoutRepository.markDeadBatch(slot, taskType, buckets.deadIds);
        if (succeeded != buckets.successIds.size() || retried != buckets.retryTimes.size()
                || dead != buckets.deadIds.size()) {
            log.info("[超时中心] 结算批实际改到的行数少于回报项数（被并发取消/已终态的行按设计不翻转）："
                            + "taskType={}, 成功 {}/{}, 重试 {}/{}, 死信 {}/{}", taskType,
                    succeeded, buckets.successIds.size(), retried, buckets.retryTimes.size(),
                    dead, buckets.deadIds.size());
        }
    }

    /**
     * 批的槽位：整批同槽（捞批是单表的），所以取批内任一行的 {@code shardSlot} 都对。
     * <p>
     * 缺失即编程错误：拿不到槽位就没法塞 hint，而没塞 hint 的条件 UPDATE 会被 ShardingSphere 的
     * 路由校验当场拒掉——显式抛出来比让它去写错表/广播强。
     */
    private static int slotOfBatch(List<TimeoutTaskEntity> batch) {
        Integer slot = batch.get(0).getShardSlot();
        if (slot == null) {
            throw new IllegalArgumentException("结算批的行上没有槽位（shardSlot）：捞批没有走仓储层？");
        }
        return slot;
    }

    /** 多桶里最早的退避时刻（只用于那条汇总 WARN） */
    private static LocalDateTime earliestOf(LocalDateTime earliest, Collection<LocalDateTime> candidates) {
        LocalDateTime result = earliest;
        for (LocalDateTime candidate : candidates) {
            if (result == null || candidate.isBefore(result)) {
                result = candidate;
            }
        }
        return result;
    }

    /** 一个 taskType 的三个互斥桶：成功项 / 重试项（键序 = 退避时刻的对应序，见仓储实现）/ 死信项 */
    private static final class BucketIds {

        private final List<String> successIds = new ArrayList<>();
        private final LinkedHashMap<String, LocalDateTime> retryTimes = new LinkedHashMap<>();
        private final List<String> deadIds = new ArrayList<>();
    }

    /**
     * 本次失败是否就是最后允许的一次（{@code retry_count} 是**已失败次数**，
     * 第 16 次失败时它恰好到 16，这一行不再重试而是进 FAILED）。
     * <p>
     * 上限兜底 ≥1：配成 0 或负数时首次失败即进死信，是合理读法；但不能让它变成「永不进死信」。
     */
    private boolean reachesRetryLimit(TimeoutTaskEntity task) {
        return failedTimes(task) >= retryLimit();
    }

    /** 本次失败之后 {@code retry_count} 会变成的值（行上的值时本次失败尚未记上） */
    private static int failedTimes(TimeoutTaskEntity task) {
        return (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
    }

    private int retryLimit() {
        return Math.max(retryMaxTimes, 1);
    }

    /**
     * 失败后的下次可触发时刻 = {@code now + base × 2^(失败次数-1)}，单次封顶 {@code max}
     * ——30s、60s、120s…。
     * <p>
     * 行上的 {@code retry_count} 是**已失败次数**：首次失败后它为 1，间隔就该是 base（30s），
     * 所以指数取 {@code 失败次数 - 1}。左移即乘 2 的幂（倍率固定 2，「指数翻倍」写死）。
     */
    private LocalDateTime nextFireTimeAfterFailure(TimeoutTaskEntity task) {
        int exponent = Math.min(Math.max(failedTimes(task) - 1, 0), MAX_BACKOFF_EXPONENT);
        // 两个配置项都兜底：底数 ≥1s、封顶 ≥1s（只兜底数是不够的——`max-seconds: 0` 会让退避算成 0，
        // next_fire_time 贴住 tick 起始时刻，那一行在同一轮里被反复捞起 → 「循环拉批到空」拉不到空，
        // 一次 tick 就把 16 次重试跑光。配置写错了也不该炸在这里）
        long cappedSeconds = Math.min(Math.max(retryBaseSeconds, 1) * (1L << exponent), Math.max(retryMaxSeconds, 1));
        long delaySeconds = Math.max(cappedSeconds, 1);
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(delaySeconds);
    }
}
