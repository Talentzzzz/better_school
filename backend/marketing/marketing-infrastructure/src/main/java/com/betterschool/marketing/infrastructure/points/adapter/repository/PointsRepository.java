package com.betterschool.marketing.infrastructure.points.adapter.repository;

import com.betterschool.marketing.domain.points.adapter.repository.IPointsRepository;
import com.betterschool.marketing.domain.points.adapter.port.config.IPointsConfigPort;
import com.betterschool.marketing.domain.points.model.entity.PointsBucketEntity;
import com.betterschool.marketing.domain.points.model.entity.PointsDetailEntity;
import com.betterschool.marketing.domain.points.model.valobj.PointsChangedMessage;
import com.betterschool.marketing.domain.points.model.valobj.PointsBucketDeductionVO;
import com.betterschool.marketing.domain.points.model.valobj.PointsDeductPlanVO;
import com.betterschool.marketing.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.marketing.domain.points.model.valobj.PointsTransactionDirectionVO;
import com.betterschool.marketing.domain.points.model.valobj.PointsTransactionVO;
import com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageStore;
import com.betterschool.marketing.infrastructure.points.dao.IPointsDao;
import com.betterschool.marketing.infrastructure.points.dao.po.PointsBucketPO;
import com.betterschool.marketing.infrastructure.points.dao.po.PointsDetailPO;
import com.betterschool.marketing.infrastructure.points.dao.po.PointsTransactionPO;
import com.betterschool.marketing.types.common.Constants;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.common.SnowflakeIdGenerator;
import com.betterschool.marketing.types.points.PointsReasonType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
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
 * 端口（{@link IPointsRepository}）上只留「一次入账」「一次消费」这样的完整动作：幂等预检、加锁读桶、
 * 写明细、改余量、发变动事件都是这两个用例的内部步骤，不对外暴露。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PointsRepository implements IPointsRepository {

    private final IPointsDao pointsDao;
    private final OutboxMessageStore outboxMessageStore;
    private final IPointsConfigPort pointsConfigPort;
    private final TransactionTemplate transactionTemplate;

    @Override
    public boolean insertBucketWithChanged(String userId, int amount, PointsReasonType reasonType, Long refId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            PointsBucketEntity bucket = newBucket(userId, amount, reasonType, refId, LocalDateTime.now());
            if (!insertBucket(bucket)) {
                return false;
            }
            int balanceAfter = sumAvailableRemain(userId);
            savePointsChanged(userId, amount, balanceAfter, reasonType, refId);
            return true;
        }));
    }

    @Override
    public PointsDeductResultVO deduct(String userId, int amount, PointsReasonType reasonType, Long refId) {
        PointsDeductResultVO result = transactionTemplate.execute(status -> {
            if (existsConsume(userId, reasonType, refId)) {
                // 重试同一笔兑换：已经扣过就按成功返回，不重复扣、不重复发事件
                log.info("[PointsRepository] 兑换扣分已存在，幂等跳过 userId={}, reasonType={}, refId={}",
                        userId, reasonType, refId);
                return success(sumAvailableRemain(userId));
            }
            List<PointsBucketEntity> buckets = listAvailableBucketsForUpdate(userId);
            int available = buckets.stream().mapToInt(PointsBucketEntity::getRemain).sum();
            if (available < amount) {
                // 一行都没动，事务空提交——不用回滚来表达业务失败
                return insufficient(available);
            }
            // 第一段：纯粹在内存里算出这笔方案（被扣光的桶 + 至多一张部分扣的桶），不碰库
            PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(buckets, amount);
            // 第二段：照这份方案原样落库
            if (!writePlan(plan, userId, reasonType, refId, LocalDateTime.now())) {
                // 并发重复兑换的唯一互斥点在这里撞上（幂等预检不承担互斥）。
                // 这里**不能**回报余额——方案里的余量变更本事务内可见，读出来是脏值；
                // 返回 null 由事务外重读真实余额，按幂等成功返回
                log.warn("[PointsRepository] 消费明细撞唯一键，判定为重复兑换 userId={}, reasonType={}, refId={}",
                        userId, reasonType, refId);
                status.setRollbackOnly();
                return null;
            }
            int balanceAfter = sumAvailableRemain(userId);
            savePointsChanged(userId, -amount, balanceAfter, reasonType, refId);
            return success(balanceAfter);
        });
        if (result == null) {
            return success(sumAvailableRemain(userId));
        }
        return result;
    }

    @Override
    public int sumAvailableRemain(String userId) {
        return pointsDao.sumAvailableRemain(userId);
    }

    @Override
    public int expireBuckets() {
        return pointsDao.expireBuckets();
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

    /** 发一条积分变动事件（走本服务 outbox，与业务同事务）：入账与消费两个用例的最后一步 */
    private void savePointsChanged(String userId, int delta, int balanceAfter, PointsReasonType reasonType, Long refId) {
        outboxMessageStore.save(Constants.POINTS_CHANGED_TOPIC,
                new PointsChangedMessage(userId, delta, balanceAfter, reasonType.name(), refId));
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
    private boolean existsConsume(String userId, PointsReasonType reasonType, Long refId) {
        return pointsDao.selectConsumeId(userId, reasonType.name(), refId) != null;
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
    private boolean writePlan(PointsDeductPlanVO plan, String userId, PointsReasonType reasonType, Long refId,
                              LocalDateTime now) {
        List<PointsBucketDeductionVO> deductions = plan.deductions();
        // 第一条：全部明细一次写完。行数偏少 = 有桶撞了 uk_bucket_reason_ref，从这一条开始回滚
        List<PointsDetailPO> details = deductions.stream()
                .map(deduction -> toDetailPO(newDetail(userId, deduction, reasonType, refId, now)))
                .toList();
        if (pointsDao.insertIgnoreDetails(details) != details.size()) {
            return false;
        }
        // 第二条：扣光的桶一条语句清零。桶已在 listAvailableBucketsForUpdate 里加了行锁，方案又按同一份 remain
        // 算出，这里必然命中集合里的每一行；少命中一行就说明余量与方案不一致
        List<Long> drainedBucketIds = plan.getDrainedBuckets().stream()
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
        PointsBucketDeductionVO partialBucket = plan.getPartialBucket();
        if (partialBucket != null) {
            int partialRows = pointsDao.deductRemain(partialBucket.getBucketId(), partialBucket.getAmount());
            if (partialRows != 1) {
                throw new IllegalStateException("部分扣的桶影响 " + partialRows + " 行，桶余量与方案不一致，整笔回滚 userId="
                        + userId + ", bucketId=" + partialBucket.getBucketId()
                        + ", amount=" + partialBucket.getAmount());
            }
        }
        return true;
    }

    /** 造一行待落的消费明细：方案的一条 + 本次消费的来源业务（同一次消费的所有行共享一个时点） */
    private PointsDetailEntity newDetail(String userId, PointsBucketDeductionVO deduction, PointsReasonType reasonType,
                                         Long refId, LocalDateTime now) {
        PointsDetailEntity detail = new PointsDetailEntity();
        detail.setUserId(userId);
        detail.setBucketId(deduction.getBucketId());
        detail.setAmount(deduction.getAmount());
        detail.setReasonType(reasonType);
        detail.setRefId(refId);
        detail.setCreateTime(now);
        detail.setUpdateTime(now);
        return detail;
    }

    private PointsDetailPO toDetailPO(PointsDetailEntity detail) {
        PointsDetailPO detailPO = new PointsDetailPO();
        detailPO.setUserId(detail.getUserId());
        detailPO.setBucketId(detail.getBucketId());
        detailPO.setAmount(detail.getAmount());
        detailPO.setReasonType(detail.getReasonType().name());
        detailPO.setRefId(detail.getRefId());
        detailPO.setCreateTime(detail.getCreateTime());
        detailPO.setUpdateTime(detail.getUpdateTime());
        return detailPO;
    }

    /** 插一行桶（{@link #insertBucketWithChanged} 的一步）；撞 uk_user_reason_ref 返回 false = 已记过 */
    private boolean insertBucket(PointsBucketEntity bucket) {
        PointsBucketPO bucketPO = new PointsBucketPO();
        bucketPO.setBucketId(bucket.getBucketId());
        bucketPO.setUserId(bucket.getUserId());
        bucketPO.setAmount(bucket.getAmount());
        bucketPO.setRemain(bucket.getRemain());
        bucketPO.setExpireAt(bucket.getExpireAt());
        bucketPO.setReasonType(bucket.getReasonType().name());
        bucketPO.setRefId(bucket.getRefId());
        bucketPO.setCreateTime(bucket.getCreateTime());
        bucketPO.setUpdateTime(bucket.getUpdateTime());
        return pointsDao.insertIgnoreBucket(bucketPO) > 0;
    }

    /** 造一行待入账的桶：bucket_id 雪花生成，expire_at = 入账时点 + 积分有效期配置（都在事务内取） */
    private PointsBucketEntity newBucket(String userId, int amount, PointsReasonType reasonType, Long refId,
                                         LocalDateTime now) {
        PointsBucketEntity bucket = new PointsBucketEntity();
        bucket.setBucketId(SnowflakeIdGenerator.nextId());
        bucket.setUserId(userId);
        bucket.setAmount(amount);
        bucket.setRemain(amount);
        bucket.setExpireAt(now.plusDays(pointsConfigPort.pointsValidDays()));
        bucket.setReasonType(reasonType);
        bucket.setRefId(refId);
        bucket.setCreateTime(now);
        bucket.setUpdateTime(now);
        return bucket;
    }

    private static PointsDeductResultVO success(int balance) {
        return new PointsDeductResultVO(true, ResponseCode.SUCCESS.getCode(),
                ResponseCode.SUCCESS.getInfo(), balance);
    }

    private static PointsDeductResultVO insufficient(int balance) {
        return new PointsDeductResultVO(false, ResponseCode.POINTS_INSUFFICIENT_POINTS.getCode(),
                ResponseCode.POINTS_INSUFFICIENT_POINTS.getInfo(), balance);
    }

    private PointsBucketEntity toBucketEntity(PointsBucketPO bucketPO) {
        PointsBucketEntity bucket = new PointsBucketEntity();
        bucket.setId(bucketPO.getId());
        bucket.setBucketId(bucketPO.getBucketId());
        bucket.setUserId(bucketPO.getUserId());
        bucket.setAmount(bucketPO.getAmount());
        bucket.setRemain(bucketPO.getRemain());
        bucket.setExpireAt(bucketPO.getExpireAt());
        bucket.setReasonType(PointsReasonType.valueOf(bucketPO.getReasonType()));
        bucket.setRefId(bucketPO.getRefId());
        bucket.setCreateTime(bucketPO.getCreateTime());
        bucket.setUpdateTime(bucketPO.getUpdateTime());
        return bucket;
    }

    private PointsTransactionVO toTransactionVO(PointsTransactionPO transactionPO) {
        PointsTransactionVO transaction = new PointsTransactionVO();
        transaction.setId(transactionPO.getId());
        transaction.setDirection(PointsTransactionDirectionVO.valueOf(transactionPO.getDirection()));
        transaction.setAmount(transactionPO.getAmount());
        transaction.setReasonType(PointsReasonType.valueOf(transactionPO.getReasonType()));
        transaction.setRefId(transactionPO.getRefId());
        transaction.setCreatedAt(transactionPO.getCreatedAt());
        transaction.setExpireAt(transactionPO.getExpireAt());
        return transaction;
    }
}
