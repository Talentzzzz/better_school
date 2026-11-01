package com.betterschool.infrastructure.adapter.repository.timeout;

import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.domain.timeout.model.valobj.TaskStatusVO;
import com.betterschool.infrastructure.dao.timeout.ITimeoutTaskDao;
import com.betterschool.infrastructure.dao.timeout.po.TimeoutTaskPO;
import com.betterschool.infrastructure.sharding.TimeoutTaskShardSlots;
import com.betterschool.types.timeout.TimeoutConstants;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * 超时任务仓储实现：把域层的原子动作翻成 SQL，并**在每次 DAO 调用前把该走的槽位塞进 HintManager**。
 * <p>
 * 分表路由全走 hint 强制路由（见 app 模块 sharding.yaml 头注释），「算槽位」是本类职责：
 * 按键读写走 {@link TimeoutTaskShardSlots#slotOf}（输入 = biz_id，与注册/扫描同一公式）；
 * **扫描的两个方向都由调用方给槽位号**——捞批给 slot 入参，结算给行上带的 {@code shardSlot}
 * （分片 job 认领的就是槽位号；一个批一个槽位，整批一条 UPDATE 也只塞这一次 hint）。
 * **别绕开本类直接调 DAO**——漏塞 hint 时写语句会被 ShardingSphere 的路由校验拒掉、读语句退化成 16 表广播。
 * 唯一例外是死信管理面的列表/计数（票 06）：那两条**故意**不塞 hint，广播正是它们要的形态。
 * <p>
 * 事务口径（backend/CLAUDE.md §1）：每个方法各自是单条语句的原子操作（幂等裁决权在库上），
 * 不需要显式事务模板；票 03 的业务事务内直插经 Spring 事务同步与本类共用同一条连接（传播天然 REQUIRED）。
 * 结算的三桶同理各是一条语句、**故意不包事务**：跨语句的中间态可接受（每行只被一条语句打中且都带未终态守卫），
 * 语义与逐行结算时代一致。
 * <p>
 * 撞唯一键在这里翻成返回值而不是异常——uk_type_biz 是幂等键，撞上属于正常业务路径
 * （同 key 重注册、终态复活），不是错误。
 */
@Repository
@RequiredArgsConstructor
public class TimeoutRepository implements ITimeoutRepository {

    private final ITimeoutTaskDao timeoutTaskDao;

    @Override
    public boolean register(TimeoutTaskEntity task) {
        if (insertIfAbsent(task)) {
            return true;
        }
        // 撞 uk_type_biz：终态行复活（重开一轮、刷新 action_time），在途行（WAITING/RETRYING）幂等不改期。
        // 裁决权在 reviveTerminal 的条件 UPDATE 上：打中 = 终态复活；没打中 = 在途 = 幂等成功（无副作用）。
        // 三分支全在库上原子裁决，与「读-判-写」的时序无关（并发注册同一键也安全）。
        return reviveTerminal(task);
    }

    @Override
    public boolean insertIfAbsent(TimeoutTaskEntity task) {
        try {
            withSlot(task.getBizId(), () -> timeoutTaskDao.insert(toPO(task)));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public TimeoutTaskEntity findByKey(String taskType, String bizId) {
        TimeoutTaskPO po = withSlot(bizId, () -> timeoutTaskDao.selectByKey(taskType, bizId));
        return po == null ? null : toEntity(po);
    }

    @Override
    public boolean reviveTerminal(TimeoutTaskEntity task) {
        return withSlot(task.getBizId(), () -> timeoutTaskDao.reviveTerminal(toPO(task))) > 0;
    }

    @Override
    public boolean cancelActive(String taskType, String bizId) {
        return withSlot(bizId, () -> timeoutTaskDao.cancelActive(taskType, bizId, now())) > 0;
    }

    @Override
    public List<TimeoutTaskEntity> selectDueBatch(int slot, LocalDateTime dueBefore, int limit) {
        return withSlotHint(slot, () -> timeoutTaskDao.selectDueBatch(dueBefore, limit)).stream()
                .map(TimeoutRepository::toEntity)
                .map(task -> withSlotOf(task, slot))
                .toList();
    }

    @Override
    public int markSuccessBatch(int slot, String taskType, List<String> bizIds) {
        if (bizIds.isEmpty()) {
            return 0;
        }
        return withSlotHint(slot, () -> timeoutTaskDao.markSuccessBatch(taskType, bizIds, now()));
    }

    @Override
    public int markRetryBatch(int slot, String taskType, LinkedHashMap<String, LocalDateTime> retryTimes) {
        if (retryTimes.isEmpty()) {
            return 0;
        }
        // 两个列表按键序一一对应（mapper 里 CASE 与 IN 各用一遍同一个键序）：
        // next_fire_time 是按行不同的那一列，键序错位就会把退避时刻写串行
        return withSlotHint(slot, () -> timeoutTaskDao.markRetryBatch(taskType,
                List.copyOf(retryTimes.keySet()), List.copyOf(retryTimes.values()), now()));
    }

    @Override
    public int markDeadBatch(int slot, String taskType, List<String> bizIds) {
        if (bizIds.isEmpty()) {
            return 0;
        }
        return withSlotHint(slot, () -> timeoutTaskDao.markDeadBatch(taskType, bizIds, now()));
    }

    @Override
    public boolean resendDeadLetter(String taskType, String bizId) {
        // next_fire_time 取 now：重发＝立刻重开一轮，下一个扫描周期就捞它
        return withSlot(bizId,
                () -> timeoutTaskDao.resendDeadLetter(taskType, bizId, now(), now())) > 0;
    }

    @Override
    public boolean cancelDeadLetter(String taskType, String bizId) {
        return withSlot(bizId, () -> timeoutTaskDao.cancelDeadLetter(taskType, bizId, now())) > 0;
    }

    @Override
    public long countDeadLetters() {
        // 死信列表按状态翻、SQL 里没有分片键：**故意不塞 hint**，让 SS 广播 16 张分表再归并
        // ——塞了 hint 会只查一张分表、翻不出全量死信。全仓只有死信管理面的这两条读走无 hint 形态。
        return timeoutTaskDao.countDeadLetters();
    }

    @Override
    public List<TimeoutTaskEntity> selectDeadLetters(int offset, int limit) {
        // 同 countDeadLetters：无 hint 广播归并（票 06）
        return timeoutTaskDao.selectDeadLetters(offset, limit).stream()
                .map(TimeoutRepository::toEntity)
                .toList();
    }

    /** 库列是 DATETIME（秒级精度）：不截秒写进去会被 MySQL 四舍五入、读回来对不上 */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 按键的读写：槽位由 bizId 算出（与注册、扫描同一个公式；hash 输入 = biz_id，见 TimeoutTaskShardSlots） */
    private <T> T withSlot(String bizId, Supplier<T> action) {
        return withSlotHint(TimeoutTaskShardSlots.slotOf(bizId), action);
    }

    /** 给从某槽位捞出来的行盖上槽位号（不落库，供结算批量语句塞 hint） */
    private static TimeoutTaskEntity withSlotOf(TimeoutTaskEntity task, int slot) {
        task.setShardSlot(slot);
        return task;
    }

    /**
     * hint 是 ThreadLocal（实为 TransmittableThreadLocal，见票 01：纪律口径是「hint 作用域必须先于
     * 子线程的创建/提交」，不是「必须同线程」）。作用域仅限这一次调用，用完即清（close() 摘掉本线程的 hint）。
     * <p>
     * 扫描的两个方向都走这里：捞批与结算三桶拿的是**槽位号**（job 认领到的 / 批上带的），
     * 所以一个批的三条 UPDATE 只塞这一次 hint——别对批量写方法去逐行算槽位，那是白算。
     * <p>
     * **两个 hint 值都塞同一个全局槽位 g**：「库 × 表」两维表达的路由侧——库维算法用 g/TABLES_PER_DB
     * 选库、表维算法用 g%TABLES_PER_DB 选表（现工程只塞表维，这里补齐两维；本期 DB_COUNT=1 两边都落 ds0）。
     */
    private <T> T withSlotHint(int slot, Supplier<T> action) {
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.addDatabaseShardingValue(TimeoutConstants.TASK_LOGIC_TABLE, slot);
            hintManager.addTableShardingValue(TimeoutConstants.TASK_LOGIC_TABLE, slot);
            return action.get();
        }
    }

    private static TimeoutTaskPO toPO(TimeoutTaskEntity task) {
        TimeoutTaskPO po = new TimeoutTaskPO();
        po.setId(task.getId());
        po.setTaskType(task.getTaskType());
        po.setBizId(task.getBizId());
        po.setActionTime(task.getActionTime());
        po.setNextFireTime(task.getNextFireTime());
        po.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        po.setRetryCount(task.getRetryCount());
        po.setCreatedAt(task.getCreatedAt());
        po.setUpdatedAt(task.getUpdatedAt());
        return po;
    }

    private static TimeoutTaskEntity toEntity(TimeoutTaskPO po) {
        TimeoutTaskEntity task = new TimeoutTaskEntity();
        task.setId(po.getId());
        task.setTaskType(po.getTaskType());
        task.setBizId(po.getBizId());
        task.setActionTime(po.getActionTime());
        task.setNextFireTime(po.getNextFireTime());
        task.setStatus(po.getStatus() == null ? null : TaskStatusVO.valueOf(po.getStatus()));
        task.setRetryCount(po.getRetryCount());
        task.setCreatedAt(po.getCreatedAt());
        task.setUpdatedAt(po.getUpdatedAt());
        return task;
    }
}
