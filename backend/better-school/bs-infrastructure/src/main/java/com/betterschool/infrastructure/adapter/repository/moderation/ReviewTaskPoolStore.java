package com.betterschool.infrastructure.adapter.repository.moderation;

import com.betterschool.domain.moderation.adapter.port.IReviewDispatchMetricsPort;
import com.betterschool.domain.moderation.adapter.port.IReviewSignalPort;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.infrastructure.dao.moderation.IModerationDao;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskPO;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 审核工单池存储（基础设施内部组件）：各域仓储在**进入待审的同事务**内调用（spec §5.1：状态迁移与开单原子，不会出现「已待审但没工单」）。
 * 入池 = 先关同内容旧轮遗留工单（旧工单停 DONE），再 INSERT IGNORE 幂等开单（uk_target_round 吸收重复）；
 * 出池 = 该目标全部在途工单停 DONE（裁决/退出待审路径，幂等 0 行无害）。
 * <p>
 * 领单/回收（spec §5.2/§5.3，票 08）：claim 是**原子指派**——同一事务内惰性回收过期租约 → FIFO 选候选 →
 * 条件 UPDATE 抢占（影响行数 1 者赢）→ 复合 bizId 注册 {@code REVIEW_LEASE_TIMEOUT} 租约超时（不引
 * SKIP LOCKED、不把 Redis 锁放关键路径、不调超时中心 cancel）；回收（超时回调 / 主动放回）按守卫条件
 * UPDATE 回 PENDING，命中 0 行＝幂等成功零副作用；不调 cancel（轮次守卫已使陈旧回调零副作用）。
 * <p>
 * 派单信号（spec §5.3「推送通道」，票 11）：真实开新工单的入池事务提交后经 {@link IReviewSignalPort}
 * 向在线审核员广播粗粒度总信号（虚拟线程异步，不阻塞业务线程）；回滚不推、同轮幂等重入不推、
 * 无人在线静默积压（不丢单，上线补推由 trigger 层连接建立路径负责）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskPoolStore {

    /** 升级阈值（spec §5.2：recycle_count ≥3 置 escalated=1 并告警；与 Mapper 内 IF 同源，改须同步） */
    static final int ESCALATE_RECYCLE_THRESHOLD = 3;

    private final IModerationDao moderationDao;
    private final ITimeoutRepository timeoutRepository;
    private final TransactionTemplate transactionTemplate;
    private final IReviewDispatchMetricsPort reviewDispatchMetrics;
    /**
     * 派单信号端口走惰性解析（ObjectProvider）：信号实现在 trigger 层、其依赖链回到本仓
     * （pushService → ReviewService → ModerationRepository → 本类），构造器直注入会成环；
     * 实际取用在入池事务提交后的广播线程里，届时容器已就绪
     */
    private final ObjectProvider<IReviewSignalPort> reviewSignalPort;

    /**
     * 入池开单（须处于业务事务内）。
     *
     * @param targetType 内容类型（ReviewTargetTypeVO 可审值 POST/COMMENT/PRODUCT/ERRAND）
     * @param targetId   内容业务 ID
     * @param reviewRound 审核轮次（与业务表 review_round 同源；COMMENT/ERRAND 恒 1）
     * @param authorId   作者用户 ID
     * @param enteredAt  进入待审时刻（FIFO 排序锚点：POST/PRODUCT 取 review_entered_at，
     *                   COMMENT 取发布时刻，ERRAND 取支付成功时刻）
     */
    public void enter(ReviewTargetTypeVO targetType, String targetId, int reviewRound, String authorId,
                      LocalDateTime enteredAt) {
        LocalDateTime now = LocalDateTime.now();
        // 同轮重提＝新轮次＝新工单行：先关旧轮遗留（业务行已在待审，旧工单必是遗留），再开新单
        moderationDao.closeStaleRoundTasks(targetType.name(), targetId, reviewRound);
        ReviewTaskPO taskPO = new ReviewTaskPO();
        taskPO.setTargetType(targetType.name());
        taskPO.setTargetId(targetId);
        taskPO.setReviewRound(reviewRound);
        taskPO.setAuthorId(authorId);
        taskPO.setSource("MANUAL");
        taskPO.setEnteredAt(enteredAt);
        taskPO.setCreateTime(now);
        taskPO.setUpdateTime(now);
        // uk_target_round 冲突 = 同轮已开单（幂等入池，0 行不报错）
        boolean inserted = moderationDao.insertIgnoreTask(taskPO) > 0;
        if (inserted) {
            // 派单信号（票 11）：真实开了新工单才广播；afterCommit 触发（回滚随事务消失、不推），
            // 虚拟线程异步执行——业务线程不得阻塞在「查积压数 + 遍历推送」上（对齐 outbox 即时发送先例）
            afterCommit(this::broadcastNewTaskSignal);
        }
    }

    /** 事务提交后广播派单信号；无活动事务（理论不可达，防御）时直接异步执行 */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    async(action);
                }
            });
        } else {
            async(action);
        }
    }

    private static void async(Runnable action) {
        Thread.ofVirtual().name("review-signal-broadcast").start(action);
    }

    private void broadcastNewTaskSignal() {
        try {
            reviewSignalPort.getObject().pushNewTaskSignal();
        } catch (Exception e) {
            // 信号漏推合法（客户端兜底：上线补推 + 计数条刷新），不反噬入池链路
            log.warn("[审核工单] 派单信号广播异常（漏推合法，客户端兜底收敛）", e);
        }
    }

    /** 出池关单（须处于业务事务内）：该目标全部在途工单停 DONE，裁决结论仍落 content_review */
    public void close(ReviewTargetTypeVO targetType, String targetId) {
        moderationDao.closeOpenTasks(targetType.name(), targetId);
    }

    /**
     * 领单（spec §5.3 原子取一件，一个仓储原子用例 = 一个事务）：
     * ① 惰性兜底——把租约已过期仍处 CLAIMED 的行放回池（防中心回调延迟/丢失把工单卡死，一行几乎免费）；
     * ② FIFO 选候选（先到先审，targetType 只作过滤器）；
     * ③ 条件 UPDATE 抢占——影响行数 1 者赢，并发下仅一人成功（败者行锁上等提交后重判 0 行落空）；
     * ④ 同事务注册 {@code REVIEW_LEASE_TIMEOUT}（复合 bizId {@code {taskId}:{claimSeq}}，action_time=领单时刻
     * +leaseMinutes）——回滚即无注册，无幽灵租约。
     * 无候选 / 抢占落空返回 empty（HTTP 层转「暂无可领工单」，不报错）。
     *
     * @param targetType   类型过滤（null = 跨类型）
     * @param claimerId    审核员用户 ID
     * @param leaseMinutes 租约时长（分钟，spec 固定 5）
     */
    public Optional<ReviewTaskPO> claim(String targetType, String claimerId, int leaseMinutes) {
        // 观测计数（票 11）随事务提交后落：惰性回收条数与是否真领到单，都在事务内捕获、提交后写 Redis
        int[] lazyRecycledHolder = {0};
        Optional<ReviewTaskPO> claimed = Optional.ofNullable(transactionTemplate.execute(status -> {
            int lazyRecycled = moderationDao.recycleExpiredLeases();
            lazyRecycledHolder[0] = lazyRecycled;
            if (lazyRecycled > 0) {
                log.warn("[审核工单] 领单前惰性回收过期租约 {} 条（超时中心回调延迟/丢失的兜底）", lazyRecycled);
                // 批量放回可能把某条推过升级阈值：SQL 内已置 escalated，这里补告警（回调/放回路径各自在行内告警）
                int escalatedInPool = moderationDao.countEscalatedPending();
                if (escalatedInPool > 0) {
                    log.warn("[审核工单告警] 惰性回收后池内已有 {} 条工单达回收阈值、被置需人工干预标记",
                            escalatedInPool);
                }
            }
            Long taskId = moderationDao.selectClaimCandidateId(targetType);
            if (taskId == null) {
                return null; // 池空
            }
            if (moderationDao.claimById(taskId, claimerId, leaseMinutes) == 0) {
                // 并发败者：候选被他人抢先（spec §5.3 影响行数 1 者赢）；不重选取下一条（一次只取一条，spec 无重试环）
                return null;
            }
            // 复合 bizId 取「本次领单后的轮次」：同事务内读，读到的就是本次 claim_seq+1 的值（同跑腿锁单先例）
            int claimSeq = moderationDao.selectClaimSeqById(taskId);
            // 租约超时注册与抢占同事务直插 timeout_task（内嵌超时中心）：回滚即无行，无幽灵租约
            timeoutRepository.register(TimeoutTaskEntity.newWaiting(Constants.REVIEW_LEASE_TIMEOUT,
                    taskId + ":" + claimSeq, LocalDateTime.now().plusMinutes(leaseMinutes), LocalDateTime.now()));
            return moderationDao.selectTaskById(taskId);
        }));
        if (lazyRecycledHolder[0] > 0) {
            // 已到此处 = 事务已提交（execute 正常返回），惰性回收的租约超时计入观测
            reviewDispatchMetrics.recordLeaseTimeout(lazyRecycledHolder[0]);
        }
        if (claimed.isPresent()) {
            reviewDispatchMetrics.recordClaim();
        }
        return claimed;
    }

    /**
     * 租约超时回调回收（轮次守卫）：按 claimSeq 条件 UPDATE 回 PENDING、清持单人、recycle_count+1；
     * 命中 0 行（已裁决/已放回/已按新轮次重领）＝幂等成功零副作用（返回 false，调用方不当失败）。
     * 达升级阈值置 escalated 标记（SQL 内）并告警日志。
     */
    public boolean recycleByRound(long taskId, int claimSeq) {
        Boolean recycled = transactionTemplate.execute(status -> {
            if (moderationDao.recycleByRound(taskId, claimSeq) == 0) {
                return false;
            }
            alertIfEscalated(taskId, "租约超时回收");
            return true;
        });
        if (Boolean.TRUE.equals(recycled)) {
            // 租约超时回收计入观测（票 11）；主动放回（releaseByClaimer）不是超时，不计
            reviewDispatchMetrics.recordLeaseTimeout(1);
        }
        return Boolean.TRUE.equals(recycled);
    }

    /**
     * 主动放回（持有人守卫）：审不了退池，CLAIMED→PENDING、recycle_count+1（spec §5.2 状态机两条
     * 回 PENDING 的边都计回收）；命中 0 行返回 false（非持有人/已裁决/已回收，调用方转冲突错误）。
     */
    public boolean releaseByClaimer(long taskId, String claimerId) {
        Boolean released = transactionTemplate.execute(status -> {
            if (moderationDao.releaseByClaimer(taskId, claimerId) == 0) {
                return false;
            }
            alertIfEscalated(taskId, "主动放回");
            return true;
        });
        return Boolean.TRUE.equals(released);
    }

    /** 回收次数达阈值置 escalated 后告警（spec §5.2「≥3 置需人工干预标记并告警」，沿用 WARN 日志告警先例） */
    private void alertIfEscalated(long taskId, String action) {
        ReviewTaskPO taskPO = moderationDao.selectTaskById(taskId);
        if (taskPO != null && taskPO.getRecycleCount() != null
                && taskPO.getRecycleCount() >= ESCALATE_RECYCLE_THRESHOLD) {
            log.warn("[审核工单告警] 工单 {} 因{}累计回收 {} 次（≥{}），已置需人工干预标记 targetType={} targetId={}",
                    taskId, action, taskPO.getRecycleCount(), ESCALATE_RECYCLE_THRESHOLD,
                    taskPO.getTargetType(), taskPO.getTargetId());
        }
    }
}
