package com.betterschool.infrastructure.timeout;

import com.betterschool.infrastructure.dao.timeout.ITimeoutTaskDao;
import com.betterschool.infrastructure.dao.timeout.po.TimeoutTask;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * 超时任务表（16 张分表）的唯一读写口：把原子动作翻成 SQL，并<b>在每次 DAO 调用前把该走的槽位塞进
 * HintManager</b>。
 * <p>
 * 分表路由全走 hint 强制路由（见 app 模块 sharding.yaml 头注释），「算槽位」是本类职责：
 * 按键读写走 {@link TimeoutTaskShardSlots#slotOf}（输入 = biz_id，与注册/扫描同一公式）；
 * <b>扫描的两个方向都由调用方给槽位号</b>——捞批给 slot 入参，结算给行上带的 {@code shardSlot}
 * （分片 job 认领的就是槽位号；一个批一个槽位，整批一条 UPDATE 也只塞这一次 hint）。
 * <b>别绕开本类直接调 DAO</b>——漏塞 hint 时写语句会被 ShardingSphere 的路由校验拒掉、
 * 读语句退化成 16 表广播。唯一例外是死信管理面的列表/计数：那两条<b>故意</b>不塞 hint，广播正是它们要的形态。
 * <p>
 * 事务口径（backend/CLAUDE.md §1）：每个方法各自是单条语句的原子操作（幂等裁决权在库上），
 * 不需要显式事务模板；业务仓储在<b>自己已经开着事务的方法</b>里调 {@link #register}，经 Spring 事务同步
 * 与本类共用同一条连接（传播天然 REQUIRED）——业务行与超时行同生同死。
 * 结算的三桶同理各是一条语句、<b>故意不包事务</b>：跨语句的中间态可接受（每行只被一条语句打中且都带未终态守卫）。
 * <p>
 * 撞唯一键在这里翻成返回值而不是异常——uk_type_biz 是幂等键，撞上属于正常路径
 * （同 key 重注册、终态复活），不是错误。
 */
@Component
@RequiredArgsConstructor
public class TimeoutTaskStore implements ITimeoutBucketWriter {

    private final ITimeoutTaskDao timeoutTaskDao;

    /**
     * 注册一条超时任务（业务事务内直插）：幂等裁决收成<b>三分支</b>——
     * <ol>
     *   <li>同键无行 → 新插入（WAITING、next_fire_time = action_time）；</li>
     *   <li>同键在途（WAITING/RETRYING）→ 幂等成功、<b>不改期</b>（沿用首次注册的时刻）；</li>
     *   <li>同键终态（SUCCESS/FAILED/CANCELED）→ 复活刷新 {@code action_time}（重开一轮）。</li>
     * </ol>
     * 原独立中心时代的「不同应用名冲突失败」随回调路由列概念消失；outbox 的「同键复位」语义由本三分支接管。
     *
     * @return true = 本次注册生效（新插入或复活）；false = 在途同键幂等吸收（无副作用）
     */
    public boolean register(TimeoutTaskEntity timeoutTaskEntity) {
        if (insertIfAbsent(timeoutTaskEntity)) {
            return true;
        }
        // 撞 uk_type_biz：终态行复活（重开一轮、刷新 action_time），在途行（WAITING/RETRYING）幂等不改期。
        // 裁决权在 reviveTerminal 的条件 UPDATE 上：打中 = 终态复活；没打中 = 在途 = 幂等成功（无副作用）。
        return reviveTerminal(timeoutTaskEntity);
    }

    /**
     * 不存在才插入。
     *
     * @return true = 新插入成功；false = 撞上 uk_type_biz（同键已有行）
     */
    public boolean insertIfAbsent(TimeoutTaskEntity timeoutTaskEntity) {
        try {
            withSlot(timeoutTaskEntity.getBizId(), () -> timeoutTaskDao.insert(toPO(timeoutTaskEntity)));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 按自然键取行；不存在返回 {@code null} */
    public TimeoutTaskEntity findByKey(String taskType, String bizId) {
        TimeoutTask timeoutTask = withSlot(bizId, () -> timeoutTaskDao.selectByKey(taskType, bizId));
        return timeoutTask == null ? null : toEntity(timeoutTask);
    }

    /**
     * 终态行复活为注册态：{@code status = WAITING}、{@code action_time = next_fire_time = 新时刻}、
     * {@code retry_count = 0}。条件 UPDATE 只打终态行（SUCCESS/FAILED/CANCELED）。
     *
     * @return true = 命中并复活；false = 行不在终态（在途，幂等不改期）
     */
    public boolean reviveTerminal(TimeoutTaskEntity timeoutTaskEntity) {
        return withSlot(timeoutTaskEntity.getBizId(), () -> timeoutTaskDao.reviveTerminal(toPO(timeoutTaskEntity))) > 0;
    }

    /**
     * 未终态行软置 CANCELED（条件 UPDATE 只打 WAITING/RETRYING）。
     *
     * @return true = 确有行被取消；false = 行不存在或已是终态（无副作用）
     */
    public boolean cancelActive(String taskType, String bizId) {
        return withSlot(bizId, () -> timeoutTaskDao.cancelActive(taskType, bizId)) > 0;
    }

    /**
     * 扫描捞批：某槽位上「未终态且已到点」的行，按 {@code next_fire_time} 升序取 {@code limit} 条。
     * <p>
     * 入参是槽位号而不是分片键——这条 SQL 里没有 {@code task_type}/{@code biz_id}，路由靠本类塞进
     * HintManager 的槽位。到期判据用调用方给的 {@code dueBefore}（不是库里的 {@code now()}）：
     * 一个 tick 内固定不动的时刻，重试行被退避推后可保证本轮不再被捞起（循环拉批到空的终止条件）。
     * <p>
     * 返回的行带上 {@code shardSlot}（= 本次入参）——结算批量语句要用<b>批的</b>槽位塞 hint。
     */
    public List<TimeoutTaskEntity> selectDueBatch(int slot, LocalDateTime dueBefore, int limit) {
        return withSlotHint(slot, () -> timeoutTaskDao.selectDueBatch(dueBefore, limit)).stream()
                .map(TimeoutTaskStore::toEntity)
                .map(task -> withSlotOf(task, slot))
                .toList();
    }

    @Override
    public int markSuccessBatch(int slot, String taskType, List<String> bizIdList) {
        if (bizIdList.isEmpty()) {
            return 0;
        }
        return withSlotHint(slot, () -> timeoutTaskDao.markSuccessBatch(taskType, bizIdList));
    }

    @Override
    public int markRetryBatch(int slot, String taskType, LinkedHashMap<String, Integer> backoffSeconds) {
        if (backoffSeconds.isEmpty()) {
            return 0;
        }
        // 两个列表按键序一一对应（mapper 里 CASE 与 IN 各用一遍同一个键序）：
        // next_fire_time 是按行不同的那一列，键序错位就会把退避时长写串行
        return withSlotHint(slot, () -> timeoutTaskDao.markRetryBatch(taskType,
                List.copyOf(backoffSeconds.keySet()), List.copyOf(backoffSeconds.values())));
    }

    @Override
    public int markDeadBatch(int slot, String taskType, List<String> bizIdList) {
        if (bizIdList.isEmpty()) {
            return 0;
        }
        return withSlotHint(slot, () -> timeoutTaskDao.markDeadBatch(taskType, bizIdList));
    }

    /**
     * 死信重发：FAILED 行置回 RETRYING、{@code retry_count} 清零、{@code next_fire_time = now}
     * ——「立即重开一轮」，<b>不动 {@code action_time}</b>（理论到期时刻不可变）。
     * 只打 FAILED 行：重发是死信专属动作。
     *
     * @return true = 确有行被重开；false = 行不是 FAILED（或不存在），无副作用
     */
    public boolean resendDeadLetter(String taskType, String bizId) {
        // next_fire_time 取 NOW()：重发＝立刻重开一轮，下一个扫描周期就捞它
        return withSlot(bizId, () -> timeoutTaskDao.resendDeadLetter(taskType, bizId)) > 0;
    }

    /**
     * 死信取消：FAILED 行置 CANCELED 终态。
     * <p>
     * 与 {@link #cancelActive}（业务取消，只打未终态行）是两条路：这里专收死信，
     * 所以「取消一个正在重试的任务」仍走业务侧，语义不混。
     */
    public boolean cancelDeadLetter(String taskType, String bizId) {
        return withSlot(bizId, () -> timeoutTaskDao.cancelDeadLetter(taskType, bizId)) > 0;
    }

    /**
     * 死信总数（管理面翻页的 total）。
     * <p>
     * <b>本组方法故意不塞 hint</b>：列表按状态翻、SQL 里没有分片键，要让 ShardingSphere 广播到
     * 16 张分表再归并——塞了 hint 会只查一张分表、翻不出全量死信（全仓唯一允许漏 hint 的两条语句）。
     */
    public long countDeadLetters() {
        return timeoutTaskDao.countDeadLetters();
    }

    /**
     * 死信列表的一页（同 {@link #countDeadLetters}：无 hint 广播归并）。
     *
     * @param offset 偏移行数（调用方按 {@code (page-1) * pageSize} 算好传入）
     * @param limit  本页行数（HTTP 边界已夹紧到 ≤ 50）
     */
    public List<TimeoutTaskEntity> selectDeadLetters(int offset, int limit) {
        return timeoutTaskDao.selectDeadLetters(offset, limit).stream()
                .map(TimeoutTaskStore::toEntity)
                .toList();
    }

    /** 按键的读写：槽位由 bizId 算出（与注册、扫描同一个公式；hash 输入 = biz_id，见 TimeoutTaskShardSlots） */
    private <T> T withSlot(String bizId, Supplier<T> action) {
        return withSlotHint(TimeoutTaskShardSlots.slotOf(bizId), action);
    }

    /** 给从某槽位捞出来的行盖上槽位号（不落库，供结算批量语句塞 hint） */
    private static TimeoutTaskEntity withSlotOf(TimeoutTaskEntity timeoutTaskEntity, int slot) {
        timeoutTaskEntity.setShardSlot(slot);
        return timeoutTaskEntity;
    }

    /**
     * hint 是 ThreadLocal（实为 TransmittableThreadLocal，见票 01：纪律口径是「hint 作用域必须先于
     * 子线程的创建/提交」，不是「必须同线程」）。作用域仅限这一次调用，用完即清（close() 摘掉本线程的 hint）。
     * <p>
     * 扫描的两个方向都走这里：捞批与结算三桶拿的是<b>槽位号</b>（job 认领到的 / 批上带的），
     * 所以一个批的三条 UPDATE 只塞这一次 hint——别对批量写方法去逐行算槽位，那是白算。
     * <p>
     * <b>两个 hint 值都塞同一个全局槽位 g</b>：「库 × 表」两维表达的路由侧——库维算法用 g/TABLES_PER_DB
     * 选库、表维算法用 g%TABLES_PER_DB 选表（本期 DB_COUNT=1 两边都落 ds0）。
     */
    private <T> T withSlotHint(int slot, Supplier<T> action) {
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.addDatabaseShardingValue(TimeoutConstants.TASK_LOGIC_TABLE, slot);
            hintManager.addTableShardingValue(TimeoutConstants.TASK_LOGIC_TABLE, slot);
            return action.get();
        }
    }

    private static TimeoutTask toPO(TimeoutTaskEntity timeoutTaskEntity) {
        TimeoutTask timeoutTask = new TimeoutTask();
        timeoutTask.setId(timeoutTaskEntity.getId());
        timeoutTask.setTaskType(timeoutTaskEntity.getTaskType());
        timeoutTask.setBizId(timeoutTaskEntity.getBizId());
        timeoutTask.setDelaySeconds(timeoutTaskEntity.getDelaySeconds());
        timeoutTask.setStatus(timeoutTaskEntity.getStatus() == null ? null : timeoutTaskEntity.getStatus().name());
        timeoutTask.setRetryCount(timeoutTaskEntity.getRetryCount());
        return timeoutTask;
    }

    private static TimeoutTaskEntity toEntity(TimeoutTask timeoutTask) {
        TimeoutTaskEntity timeoutTaskEntity = new TimeoutTaskEntity();
        timeoutTaskEntity.setId(timeoutTask.getId());
        timeoutTaskEntity.setTaskType(timeoutTask.getTaskType());
        timeoutTaskEntity.setBizId(timeoutTask.getBizId());
        timeoutTaskEntity.setActionTime(timeoutTask.getActionTime());
        timeoutTaskEntity.setNextFireTime(timeoutTask.getNextFireTime());
        timeoutTaskEntity.setStatus(timeoutTask.getStatus() == null ? null : TimeoutTaskStatusEnum.valueOf(timeoutTask.getStatus()));
        timeoutTaskEntity.setRetryCount(timeoutTask.getRetryCount());
        timeoutTaskEntity.setCreatedAt(timeoutTask.getCreatedAt());
        timeoutTaskEntity.setUpdatedAt(timeoutTask.getUpdatedAt());
        return timeoutTaskEntity;
    }
}
