package com.betterschool.marketing.infrastructure.drawcount.adapter.repository;

import com.betterschool.marketing.domain.drawcount.adapter.port.config.IDrawCountConfigPort;
import com.betterschool.marketing.domain.drawcount.adapter.repository.IDrawCountRepository;
import com.betterschool.marketing.domain.drawcount.model.entity.DrawCountBucketEntity;
import com.betterschool.marketing.domain.drawcount.model.entity.DrawCountDetailEntity;
import com.betterschool.marketing.infrastructure.drawcount.dao.IDrawCountDao;
import com.betterschool.marketing.infrastructure.drawcount.dao.po.DrawCountBucketPO;
import com.betterschool.marketing.infrastructure.drawcount.dao.po.DrawCountDetailPO;
import com.betterschool.marketing.types.common.SnowflakeIdGenerator;
import com.betterschool.marketing.types.drawcount.DrawCountReasonType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖次数仓储实现：数据访问与 PO ⇄ 领域对象映射，外加两次「一次操作 = 一个原子用例」的事务边界
 * （入账、扣次，都由本类用 {@code TransactionTemplate} REQUIRED 包住），桶的分配算法在域服务里。
 * 「流水先行」与积分侧同构——先 INSERT IGNORE 明细（撞 uk = 已处理过），再动余量；
 * 这个顺序同时是并发重复抽奖的互斥点：后到者插不进同一间隙，撞 uk 后整事务回滚。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DrawCountRepository implements IDrawCountRepository {

    private final IDrawCountDao drawCountDao;
    private final IDrawCountConfigPort drawCountConfigPort;
    private final TransactionTemplate transactionTemplate;

    @Override
    public boolean creditBucket(String userId, int amount, DrawCountReasonType reasonType, Long refId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                insertBucket(newBucket(userId, amount, reasonType, refId, LocalDateTime.now()))));
    }

    @Override
    public boolean deductForDraw(String userId, Long recordId) {
        Boolean result = transactionTemplate.execute(status -> {
            if (existsDetailForUpdate(recordId)) {
                // 同一抽奖记录重放：幂等跳过，不重复扣次
                log.info("[DrawCountRepository] 扣次明细已存在，幂等跳过 userId={}, recordId={}", userId, recordId);
                return Boolean.TRUE;
            }
            List<DrawCountBucketEntity> buckets = listAvailableBucketsForUpdate(userId);
            if (buckets.isEmpty()) {
                // 一行都没动，事务空提交——不用回滚来表达业务失败
                return Boolean.FALSE;
            }
            // 每次抽奖恒扣 1 次：最早的可用桶必有 remain >= 1，单个桶即扣完，不跨桶
            DrawCountBucketEntity bucket = buckets.get(0);
            LocalDateTime now = LocalDateTime.now();
            DrawCountDetailEntity detail = new DrawCountDetailEntity();
            detail.setUserId(userId);
            detail.setBucketId(bucket.getBucketId());
            detail.setRefId(recordId);
            detail.setCreateTime(now);
            detail.setUpdateTime(now);
            if (!consumeBucket(detail)) {
                // 兜底（预检应当已拦住）：同一笔抽奖已被并发事务扣过 ⇒ 整事务回滚
                log.warn("[DrawCountRepository] 扣次明细撞唯一键，判定为重复抽奖 userId={}, bucketId={}, recordId={}",
                        userId, bucket.getBucketId(), recordId);
                status.setRollbackOnly();
                return null;
            }
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean existsDetailForUpdate(Long refId) {
        return drawCountDao.selectDetailIdForUpdate(refId) != null;
    }

    @Override
    public List<DrawCountBucketEntity> listAvailableBucketsForUpdate(String userId) {
        return drawCountDao.selectAvailableBucketsForUpdate(userId).stream().map(this::toBucketEntity).toList();
    }

    @Override
    public boolean consumeBucket(DrawCountDetailEntity detail) {
        DrawCountDetailPO detailPO = new DrawCountDetailPO();
        detailPO.setUserId(detail.getUserId());
        detailPO.setBucketId(detail.getBucketId());
        detailPO.setRefId(detail.getRefId());
        detailPO.setCreateTime(detail.getCreateTime());
        detailPO.setUpdateTime(detail.getUpdateTime());
        if (drawCountDao.insertIgnoreDetail(detailPO) == 0) {
            return false;
        }
        // 桶已在 listAvailableBucketsForUpdate 里加了行锁，这里必然命中 1 行；命中 0 行说明不变量被破坏
        if (drawCountDao.deductRemain(detail.getBucketId()) == 0) {
            throw new IllegalStateException("扣减次数桶余量影响 0 行，桶余量与加锁读结果不一致 bucketId="
                    + detail.getBucketId());
        }
        return true;
    }

    @Override
    public int sumAvailableRemain(String userId) {
        return drawCountDao.sumAvailableRemain(userId);
    }

    @Override
    public int expireBuckets() {
        return drawCountDao.expireBuckets();
    }

    // ---------------------------------------------------------------- 内部

    /** 插一行桶（{@link #creditBucket} 的一步）；撞 uk_user_reason_ref 返回 false = 已记过 */
    private boolean insertBucket(DrawCountBucketEntity bucket) {
        DrawCountBucketPO bucketPO = new DrawCountBucketPO();
        bucketPO.setBucketId(bucket.getBucketId());
        bucketPO.setUserId(bucket.getUserId());
        bucketPO.setAmount(bucket.getAmount());
        bucketPO.setRemain(bucket.getRemain());
        bucketPO.setExpireAt(bucket.getExpireAt());
        bucketPO.setReasonType(bucket.getReasonType().name());
        bucketPO.setRefId(bucket.getRefId());
        bucketPO.setCreateTime(bucket.getCreateTime());
        bucketPO.setUpdateTime(bucket.getUpdateTime());
        return drawCountDao.insertIgnoreBucket(bucketPO) > 0;
    }

    /** 造一行待入账的桶：bucket_id 雪花生成，expire_at = 入账时点 + 次数有效期配置（都在事务内取） */
    private DrawCountBucketEntity newBucket(String userId, int amount, DrawCountReasonType reasonType, Long refId,
                                            LocalDateTime now) {
        DrawCountBucketEntity bucket = new DrawCountBucketEntity();
        bucket.setBucketId(SnowflakeIdGenerator.nextId());
        bucket.setUserId(userId);
        bucket.setAmount(amount);
        bucket.setRemain(amount);
        bucket.setExpireAt(now.plusDays(drawCountConfigPort.drawCountValidDays()));
        bucket.setReasonType(reasonType);
        bucket.setRefId(refId);
        bucket.setCreateTime(now);
        bucket.setUpdateTime(now);
        return bucket;
    }

    private DrawCountBucketEntity toBucketEntity(DrawCountBucketPO bucketPO) {
        DrawCountBucketEntity bucket = new DrawCountBucketEntity();
        bucket.setId(bucketPO.getId());
        bucket.setBucketId(bucketPO.getBucketId());
        bucket.setUserId(bucketPO.getUserId());
        bucket.setAmount(bucketPO.getAmount());
        bucket.setRemain(bucketPO.getRemain());
        bucket.setExpireAt(bucketPO.getExpireAt());
        bucket.setReasonType(DrawCountReasonType.valueOf(bucketPO.getReasonType()));
        bucket.setRefId(bucketPO.getRefId());
        bucket.setCreateTime(bucketPO.getCreateTime());
        bucket.setUpdateTime(bucketPO.getUpdateTime());
        return bucket;
    }
}
