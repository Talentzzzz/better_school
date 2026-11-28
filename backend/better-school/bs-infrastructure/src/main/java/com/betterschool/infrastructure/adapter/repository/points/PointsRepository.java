package com.betterschool.infrastructure.adapter.repository.points;

import com.betterschool.domain.points.adapter.repository.IPointsRepository;
import com.betterschool.domain.points.model.entity.PointsBucketEntity;
import com.betterschool.domain.points.model.entity.PointsDetailEntity;
import com.betterschool.types.common.PointsChangedMessage;
import com.betterschool.domain.points.model.valobj.PointsBucketDeductionVO;
import com.betterschool.domain.points.model.valobj.PointsDeductPlanVO;
import com.betterschool.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.domain.points.model.valobj.PointsTransactionDirectionEnum;
import com.betterschool.domain.points.model.valobj.PointsTransactionVO;
import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.betterschool.infrastructure.dao.points.IPointsDao;
import com.betterschool.infrastructure.dao.points.IPointsDetailDao;
import com.betterschool.infrastructure.dao.points.IPointsValidityConfigDao;
import com.betterschool.infrastructure.dao.points.po.PointsBucketPO;
import com.betterschool.infrastructure.dao.points.po.PointsDetailPO;
import com.betterschool.infrastructure.dao.points.po.PointsMergeGroupPO;
import com.betterschool.infrastructure.dao.points.po.PointsTransactionPO;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.id.IIdGenerator;
import com.betterschool.types.points.PointsReasonTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 积分仓储实现：数据访问与 PO ⇄ 领域对象映射，外加两次「一次操作 = 一个原子用例」的事务边界
 * （入账、消费，都由本类用 {@code TransactionTemplate} REQUIRED 包住）。
 * <p>
 * 消费分两段：{@link PointsDeductPlanVO#allocate} 在内存里算出方案（扣光的桶 + 至多一张部分扣的桶），
 * {@link #writePlan} 再把这份方案照原样落库——**至多三条语句**（一条多值写全部明细、一条清零扣光的桶、
 * 一条扣那张部分扣的桶），落库语句数与桶数无关。「流水先行」照仓库惯例——先 INSERT IGNORE 明细
 * （撞 uk = 已处理过），再动余量；这个顺序同时是并发重复兑换的唯一互斥点，理由见 {@link #existsConsume}。
 * <p>
 * 关键不变量：余额永远是未过期且未扣完的桶 remain 之和，读路径一律带 {@code expire_at > NOW()}。
 * <p>
 * 端口（{@link IPointsRepository}）上只留「一次入账」「一次消费」这样的完整动作：幂等预检、加锁读桶、
 * 写明细、改余量、发变动事件都是这两个用例的内部步骤，不对外暴露。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PointsRepository implements IPointsRepository {

    private final IPointsDao pointsDao;
    private final IPointsDetailDao pointsDetailDao;
    private final IPointsValidityConfigDao pointsValidityConfigDao;
    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final IIdGenerator idGenerator;

    @Override
    public boolean insertBucketWithChanged(String userId, int amount, PointsReasonTypeEnum pointsReasonType, String refId) {
        List<MqOutbox> pendingOutboxList = new ArrayList<>(1);
        boolean inserted = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            PointsBucketEntity pointsBucketEntity = newBucket(userId, amount, pointsReasonType, refId, LocalDateTime.now());
            if (!insertBucket(pointsBucketEntity)) {
                return false;
            }
            int balanceAfter = sumAvailableRemain(userId);
            pendingOutboxList.add(savePointsChanged(userId, amount, balanceAfter, pointsReasonType, refId));
            return true;
        }));
        // 事务已提交：事务外同步发送并更新 outbox 状态，失败留 PENDING 交 outboxScan 兜底
        sendOutboxes(pendingOutboxList);
        return inserted;
    }

    @Override
    public PointsDeductResultVO deduct(String userId, int amount, PointsReasonTypeEnum pointsReasonType, String refId) {
        List<MqOutbox> pendingOutboxList = new ArrayList<>(1);
        PointsDeductResultVO pointsDeductResultVO = transactionTemplate.execute(status -> {
            if (existsConsume(userId, pointsReasonType, refId)) {
                // 重试同一笔兑换：已经扣过就按成功返回，不重复扣、不重复发事件
                log.info("[PointsRepository] 兑换扣分已存在，幂等跳过 userId={}, reasonType={}, refId={}",
                        userId, pointsReasonType, refId);
                return success(sumAvailableRemain(userId));
            }
            List<PointsBucketEntity> buckets = listAvailableBucketsForUpdate(userId);
            int available = buckets.stream().mapToInt(PointsBucketEntity::getRemain).sum();
            if (available < amount) {
                // 一行都没动，事务空提交——不用回滚来表达业务失败
                return insufficient(available);
            }
            // 第一段：纯粹在内存里算出这笔方案（被扣光的桶 + 至多一张部分扣的桶），不碰库
            PointsDeductPlanVO pointsDeductPlanVO = PointsDeductPlanVO.allocate(buckets, amount);
            // 第二段：照这份方案原样落库
            if (!writePlan(pointsDeductPlanVO, userId, pointsReasonType, refId, LocalDateTime.now())) {
                // 并发重复兑换的唯一互斥点在这里撞上（幂等预检不承担互斥）。
                // 这里**不能**回报余额——方案里的余量变更本事务内可见，读出来是脏值；
                // 返回 null 由事务外重读真实余额，按幂等成功返回
                log.warn("[PointsRepository] 消费明细撞唯一键，判定为重复兑换 userId={}, reasonType={}, refId={}",
                        userId, pointsReasonType, refId);
                status.setRollbackOnly();
                return null;
            }
            int balanceAfter = sumAvailableRemain(userId);
            pendingOutboxList.add(savePointsChanged(userId, -amount, balanceAfter, pointsReasonType, refId));
            return success(balanceAfter);
        });
        // 事务已提交：事务外同步发送并更新 outbox 状态，失败留 PENDING 交 outboxScan 兜底
        sendOutboxes(pendingOutboxList);
        if (pointsDeductResultVO == null) {
            return success(sumAvailableRemain(userId));
        }
        return pointsDeductResultVO;
    }

    @Override
    public int sumAvailableRemain(String userId) {
        return pointsDao.sumAvailableRemain(userId);
    }

    @Override
    public int compactSmallBuckets() {
        // 第一步（无事务）：扫全表拿候选用户批（普通快照读，不加锁）；
        // 真正的互斥在第二步——加锁重读小桶时桶行锁上串行，双跑/并发合并天然安全
        List<PointsMergeGroupPO> candidates = pointsDao.selectMergeCandidateGroups(
                com.betterschool.types.points.Constants.COMPACTION_BATCH_LIMIT);
        int mergedBuckets = 0;
        // 逐用户一事务：一个用户的合并失败不拖累其他用户，下一轮任务会重扫补上
        for (PointsMergeGroupPO pointsMergeGroupPO : candidates) {
            mergedBuckets += transactionTemplate.execute(status ->
                    compactOneUser(pointsMergeGroupPO.getUserId()));
        }
        return mergedBuckets;
    }

    /**
     * 单用户合并（一个事务）：加锁重读该用户的小额桶 → 少于 2 张跳过 → 插一桶新桶（amount = remain =
     * SUM，expire_at = max——把小桶有效期延长到其中最晚到期，产品口径）→ 旧桶全部置 0（不删行，
     * 桶表兼做发放流水，原始入账记录留审计）。
     * <p>
     * 守恒：SUM(remain) 合并前后不变（0+0+…+S = S），余额读不受影响；新桶 expire_at = max 天然排在
     * FIFO 扣减顺序最后，先到期先扣的次序不变。并发双跑靠桶行锁串行：后到者拿锁重读时旧桶已清零、
     * 不再满足条件，自然跳过。
     *
     * @return 本用户实际清零（=合并）的旧桶数；0 = 加锁重读后不足 2 张（并发先到者已合并过）
     */
    private int compactOneUser(String userId) {
        List<PointsBucketPO> smallBuckets = pointsDao.selectSmallBucketsForUpdate(userId);
        if (smallBuckets.size() < com.betterschool.types.points.Constants.MERGE_MIN_BUCKETS) {
            return 0;
        }
        int total = smallBuckets.stream().mapToInt(PointsBucketPO::getRemain).sum();
        LocalDateTime maxExpireAt = smallBuckets.stream()
                .map(PointsBucketPO::getExpireAt)
                .max(LocalDateTime::compareTo)
                .orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        // 合并产生的是一笔新的资产形态：新插一桶（reason_type = COMPACTION，ref_id = 合并批次号），
        // 旧行是发放流水（amount/ref_id 记着原始入账来源），不能被改写、只能归零
        PointsBucketEntity pointsBucketEntity = new PointsBucketEntity();
        pointsBucketEntity.setBucketId(idGenerator.nextId());
        pointsBucketEntity.setUserId(userId);
        pointsBucketEntity.setAmount(total);
        pointsBucketEntity.setRemain(total);
        pointsBucketEntity.setExpireAt(maxExpireAt);
        pointsBucketEntity.setReasonType(PointsReasonTypeEnum.COMPACTION);
        pointsBucketEntity.setRefId(idGenerator.nextId());
        pointsBucketEntity.setCreateTime(now);
        pointsBucketEntity.setUpdateTime(now);
        if (!insertBucket(pointsBucketEntity)) {
            throw new IllegalStateException("合并桶插入失败（uk_user_reason_ref 撞键不该发生：ref_id 是合并批次号），"
                    + "整笔回滚 userId=" + userId);
        }
        List<String> oldBucketIds = smallBuckets.stream().map(PointsBucketPO::getBucketId).toList();
        int drainedRows = pointsDao.drainBuckets(userId, oldBucketIds);
        if (drainedRows != oldBucketIds.size()) {
            throw new IllegalStateException("合并清零旧桶影响 " + drainedRows + " 行，与加锁重读的 "
                    + oldBucketIds.size() + " 张不一致，整笔回滚 userId=" + userId);
        }
        return drainedRows;
    }

    @Override
    public List<PointsTransactionVO> listTransactions(String userId, int offset, int limit) {
        return pointsDao.selectTransactions(userId, offset, limit).stream().map(this::toTransactionVO).toList();
    }

    @Override
    public long countTransactions(String userId) {
        return pointsDao.countTransactions(userId);
    }

    // ---------------------------------------------------------------- 内部

    /** 事务内落一条积分变动事件（走本服务 outbox，与业务同事务），返回待事务后发送的行 */
    private MqOutbox savePointsChanged(String userId, int delta, int balanceAfter, PointsReasonTypeEnum pointsReasonType,
                                       String refId) {
        return saveOutbox(Constants.POINTS_CHANGED_TOPIC,
                new PointsChangedMessage(userId, delta, balanceAfter, pointsReasonType.name(), refId));
    }

    /** 事务内只做 DAO 写：落一行 PENDING outbox，发送交给事务提交后的调用点 */
    private MqOutbox saveOutbox(String destination, Object payload) {
        MqOutbox mqOutbox = MqOutbox.pending(destination, payload, objectMapper);
        mqOutboxDao.insert(mqOutbox);
        return mqOutbox;
    }

    /**
     * 事务外同步发送并更新 outbox 状态：成功标记 SENT，失败累计重试次数（到上限置死信），留 PENDING 交
     * outboxScan 兜底。仓储事务是 REQUIRED——若并入调用方事务（执行后事务仍活跃），不在提交前发送，
     * 留给扫描兜底，避免「消息已发、外层事务回滚」。
     */
    private void sendOutboxes(List<MqOutbox> outboxList) {
        if (outboxList.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.debug("[PointsRepository] outbox 并入调用方事务，留给 outboxScan 兜底 msgIds={}",
                    outboxList.stream().map(MqOutbox::getMsgId).toList());
            return;
        }
        for (MqOutbox mqOutbox : outboxList) {
            sendOutbox(mqOutbox);
        }
    }

    private void sendOutbox(MqOutbox mqOutbox) {
        boolean sent;
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(mqOutbox.getDestination(), mqOutbox.getPayload());
            sent = sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
            if (!sent) {
                log.warn("outbox 同步发送未确认 msgId={}, status={}", mqOutbox.getMsgId(),
                        sendResult == null ? null : sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.warn("outbox 同步发送失败 msgId={}", mqOutbox.getMsgId(), e);
            sent = false;
        }
        if (sent) {
            mqOutboxDao.markSentByMsgId(mqOutbox.getMsgId());
        } else if (mqOutbox.getRetryCount() + 1 >= MqOutbox.MAX_RETRY_COUNT) {
            mqOutboxDao.markDeadBatch(List.of(mqOutbox.getId()));
        } else {
            mqOutboxDao.incrementRetryBatch(List.of(mqOutbox.getId()));
        }
    }

    /**
     * 幂等预检的快路径：普通快照读，命中即跳过整笔消费。
     * <p>
     * 它**不是**并发互斥手段，加锁读也不是：该查询在「这笔没扣过」时结果为空，加锁读加的只是间隙锁，
     * 而间隙锁彼此兼容、并不互斥，两个并发事务都能通过；它唯一的实际效果是在「空查 + 后续插入」
     * 这个 InnoDB 常见组合上埋下死锁，所以这里只做普通快照读。
     * <p>
     * 真正的互斥是消费明细的唯一键（{@code uk_bucket_reason_ref}）：后到者写明细时撞 uk，整事务回滚、
     * 事务外重读余额按成功回报。代价只落在「本事务开始之后才提交的重复请求」这一窄窗口——预检漏判，
     * 通常由该唯一键兜底；但若该窗口内余额已被先到者扣到不足，会先走「积分不足」分支、唯一键没机会出场
     * （既有行为，本方法不处理，见票 03 的记录）。
     * <p>
     * 同一窗口里还有一条更窄的路径：后到者的方案若只落在先到者**没碰过**的桶上（先到者恰好扣光最老那张、
     * 后面还剩够用的桶），明细行数对得上，唯一键也不出场。逐桶版同样如此，不是本次改动引入的；
     * 要收它得改幂等键的粒度（例如按兑换单号唯一），那是另开票的事。
     */
    private boolean existsConsume(String userId, PointsReasonTypeEnum pointsReasonType, String refId) {
        return pointsDetailDao.selectConsumeId(userId, pointsReasonType.name(), refId) != null;
    }

    /** 当前可用桶（{@code remain > 0 AND expire_at > NOW()}），按 FIFO（expire_at, id）加行锁 */
    private List<PointsBucketEntity> listAvailableBucketsForUpdate(String userId) {
        return pointsDao.selectAvailableBucketsForUpdate(userId).stream().map(this::toBucketEntity).toList();
    }

    /**
     * 照方案落库：**至多三条语句**，与桶数无关——跨 1 个桶与跨 50 个桶的上界一样是三条
     * （跨 1 个桶时后两条退化为单行更新，其中「扣光的桶」或「部分扣的桶」缺一个就少一条）。
     * <ol>
     *   <li>一条多值 {@code INSERT IGNORE} 写全部消费明细（影响行数 = 明细条数 = 被碰到的桶数）</li>
     *   <li>一条 {@code UPDATE ... SET remain = 0 WHERE bucket_id IN (...)} 把扣光的桶清零
     *       （影响行数 = 扣光集合大小；集合为空时这条不执行——空 IN 不是合法 SQL）</li>
     *   <li>一条 {@code UPDATE ... SET remain = remain - ?} 扣那张部分扣的桶（影响行数 = 1；
     *       整笔恰好扣完、没有部分扣的桶时这条跳过）</li>
     * </ol>
     * 明细先行有两层含义：① 唯一键是全链路最早的重复判定点，先插明细才可能在动余量之前发现重复；
     * ② 撞键时余量一行都没改过，整笔回滚就是唯一结果，不存在「明细没写而余量已减」的半扣。
     * <p>
     * 三条语句各校验影响行数：第 1 条对不上是**重复兑换**（撞 uk，回滚后按幂等成功回报）；
     * 第 2、3 条对不上是**不变量被破坏**（桶余量与方案不一致），抛异常整笔回滚，绝不静默继续。
     *
     * @return false = 明细的影响行数少于明细条数（撞了 {@code uk_bucket_reason_ref}），这些桶里已有这一笔业务
     */
    private boolean writePlan(PointsDeductPlanVO pointsDeductPlanVO, String userId, PointsReasonTypeEnum pointsReasonType, String refId,
                              LocalDateTime now) {
        List<PointsBucketDeductionVO> deductions = pointsDeductPlanVO.deductions();
        // 第一条：全部明细一次写完。行数偏少 = 有桶撞了 uk_bucket_reason_ref，从这一条开始回滚
        List<PointsDetailPO> details = deductions.stream()
                .map(deduction -> toDetailPO(newDetail(userId, deduction, pointsReasonType, refId, now)))
                .toList();
        if (pointsDetailDao.insertIgnoreDetails(details) != details.size()) {
            return false;
        }
        // 第二条：扣光的桶一条语句清零。桶已在 listAvailableBucketsForUpdate 里加了行锁，方案又按同一份 remain
        // 算出，这里必然命中集合里的每一行；少命中一行就说明余量与方案不一致
        List<String> drainedBucketIds = pointsDeductPlanVO.getDrainedBuckets().stream()
                .map(PointsBucketDeductionVO::getBucketId)
                .toList();
        if (!drainedBucketIds.isEmpty()) {
            int drainedRows = pointsDao.drainBuckets(userId, drainedBucketIds);
            if (drainedRows != drainedBucketIds.size()) {
                throw new IllegalStateException("扣光的桶清零影响 " + drainedRows + " 行，与方案的 "
                        + drainedBucketIds.size() + " 个桶不一致，整笔回滚 userId=" + userId);
            }
        }
        // 第三条：方案里唯一那张部分扣的桶（恰好整笔扣完时不存在，这条跳过）
        PointsBucketDeductionVO pointsBucketDeductionVO = pointsDeductPlanVO.getPartialBucket();
        if (pointsBucketDeductionVO != null) {
            int partialRows = pointsDao.deductRemain(pointsBucketDeductionVO.getBucketId(), pointsBucketDeductionVO.getAmount());
            if (partialRows != 1) {
                throw new IllegalStateException("部分扣的桶影响 " + partialRows + " 行，桶余量与方案不一致，整笔回滚 userId="
                        + userId + ", bucketId=" + pointsBucketDeductionVO.getBucketId()
                        + ", amount=" + pointsBucketDeductionVO.getAmount());
            }
        }
        return true;
    }

    /** 造一行待落的消费明细：方案的一条 + 本次消费的来源业务（同一次消费的所有行共享一个时点） */
    private PointsDetailEntity newDetail(String userId, PointsBucketDeductionVO pointsBucketDeductionVO, PointsReasonTypeEnum pointsReasonType,
                                         String refId, LocalDateTime now) {
        PointsDetailEntity pointsDetailEntity = new PointsDetailEntity();
        pointsDetailEntity.setUserId(userId);
        pointsDetailEntity.setBucketId(pointsBucketDeductionVO.getBucketId());
        pointsDetailEntity.setAmount(pointsBucketDeductionVO.getAmount());
        pointsDetailEntity.setReasonType(pointsReasonType);
        pointsDetailEntity.setRefId(refId);
        pointsDetailEntity.setCreateTime(now);
        pointsDetailEntity.setUpdateTime(now);
        return pointsDetailEntity;
    }

    private PointsDetailPO toDetailPO(PointsDetailEntity pointsDetailEntity) {
        PointsDetailPO pointsDetailPO = new PointsDetailPO();
        pointsDetailPO.setUserId(pointsDetailEntity.getUserId());
        pointsDetailPO.setBucketId(pointsDetailEntity.getBucketId());
        pointsDetailPO.setAmount(pointsDetailEntity.getAmount());
        pointsDetailPO.setReasonType(pointsDetailEntity.getReasonType().name());
        pointsDetailPO.setRefId(pointsDetailEntity.getRefId());
        pointsDetailPO.setCreateTime(pointsDetailEntity.getCreateTime());
        pointsDetailPO.setUpdateTime(pointsDetailEntity.getUpdateTime());
        return pointsDetailPO;
    }

    /** 插一行桶（{@link #insertBucketWithChanged} 的一步）；撞 uk_user_reason_ref 返回 false = 已记过 */
    private boolean insertBucket(PointsBucketEntity pointsBucketEntity) {
        PointsBucketPO pointsBucketPO = new PointsBucketPO();
        pointsBucketPO.setBucketId(pointsBucketEntity.getBucketId());
        pointsBucketPO.setUserId(pointsBucketEntity.getUserId());
        pointsBucketPO.setAmount(pointsBucketEntity.getAmount());
        pointsBucketPO.setRemain(pointsBucketEntity.getRemain());
        pointsBucketPO.setExpireAt(pointsBucketEntity.getExpireAt());
        pointsBucketPO.setReasonType(pointsBucketEntity.getReasonType().name());
        pointsBucketPO.setRefId(pointsBucketEntity.getRefId());
        pointsBucketPO.setCreateTime(pointsBucketEntity.getCreateTime());
        pointsBucketPO.setUpdateTime(pointsBucketEntity.getUpdateTime());
        return pointsDao.insertIgnoreBucket(pointsBucketPO) > 0;
    }

    /** 造一行待入账的桶：bucket_id 走统一 IdGenerator 发号（uuid-biz-id 票 07），expire_at = 入账时点 + 积分有效期配置（都在事务内取） */
    private PointsBucketEntity newBucket(String userId, int amount, PointsReasonTypeEnum pointsReasonType, String refId,
                                         LocalDateTime now) {
        PointsBucketEntity pointsBucketEntity = new PointsBucketEntity();
        pointsBucketEntity.setBucketId(idGenerator.nextId());
        pointsBucketEntity.setUserId(userId);
        pointsBucketEntity.setAmount(amount);
        pointsBucketEntity.setRemain(amount);
        pointsBucketEntity.setExpireAt(now.plusDays(validDays()));
        pointsBucketEntity.setReasonType(pointsReasonType);
        pointsBucketEntity.setRefId(refId);
        pointsBucketEntity.setCreateTime(now);
        pointsBucketEntity.setUpdateTime(now);
        return pointsBucketEntity;
    }

    @Override
    public int validDays() {
        Integer validDays = pointsValidityConfigDao.selectPointsValidDays();
        if (validDays == null) {
            throw new IllegalStateException("points_validity_config 缺单行配置——种子或迁移没跑");
        }
        return validDays;
    }

    @Override
    public void updateValidDays(int days) {
        if (pointsValidityConfigDao.updatePointsValidDays(days) == 0) {
            throw new IllegalStateException("points_validity_config 缺单行配置，更新无效——种子或迁移没跑");
        }
    }

    private static PointsDeductResultVO success(int balance) {
        return new PointsDeductResultVO(true, ResponseCodeEnum.SUCCESS.getCode(),
                ResponseCodeEnum.SUCCESS.getInfo(), balance);
    }

    private static PointsDeductResultVO insufficient(int balance) {
        return new PointsDeductResultVO(false, ResponseCodeEnum.POINTS_INSUFFICIENT_POINTS.getCode(),
                ResponseCodeEnum.POINTS_INSUFFICIENT_POINTS.getInfo(), balance);
    }

    private PointsBucketEntity toBucketEntity(PointsBucketPO pointsBucketPO) {
        PointsBucketEntity pointsBucketEntity = new PointsBucketEntity();
        pointsBucketEntity.setId(pointsBucketPO.getId());
        pointsBucketEntity.setBucketId(pointsBucketPO.getBucketId());
        pointsBucketEntity.setUserId(pointsBucketPO.getUserId());
        pointsBucketEntity.setAmount(pointsBucketPO.getAmount());
        pointsBucketEntity.setRemain(pointsBucketPO.getRemain());
        pointsBucketEntity.setExpireAt(pointsBucketPO.getExpireAt());
        pointsBucketEntity.setReasonType(PointsReasonTypeEnum.valueOf(pointsBucketPO.getReasonType()));
        pointsBucketEntity.setRefId(pointsBucketPO.getRefId());
        pointsBucketEntity.setCreateTime(pointsBucketPO.getCreateTime());
        pointsBucketEntity.setUpdateTime(pointsBucketPO.getUpdateTime());
        return pointsBucketEntity;
    }

    private PointsTransactionVO toTransactionVO(PointsTransactionPO pointsTransactionPO) {
        PointsTransactionVO pointsTransactionVO = new PointsTransactionVO();
        pointsTransactionVO.setId(pointsTransactionPO.getId());
        pointsTransactionVO.setDirection(PointsTransactionDirectionEnum.valueOf(pointsTransactionPO.getDirection()));
        pointsTransactionVO.setAmount(pointsTransactionPO.getAmount());
        pointsTransactionVO.setReasonType(PointsReasonTypeEnum.valueOf(pointsTransactionPO.getReasonType()));
        pointsTransactionVO.setRefId(pointsTransactionPO.getRefId());
        pointsTransactionVO.setCreatedAt(pointsTransactionPO.getCreatedAt());
        pointsTransactionVO.setExpireAt(pointsTransactionPO.getExpireAt());
        return pointsTransactionVO;
    }
}
