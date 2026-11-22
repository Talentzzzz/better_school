package com.betterschool.marketing.infrastructure.points.dao;

import com.betterschool.marketing.infrastructure.points.dao.po.PointsBucketPO;
import com.betterschool.marketing.infrastructure.points.dao.po.PointsMergeGroupPO;
import com.betterschool.marketing.infrastructure.points.dao.po.PointsTransactionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 积分桶表（points_bucket）Mapper：一表一 DAO（明细表见 IPointsDetailDao；
 * 流水查询是两表 UNION，无单一主表，留在主表 points_bucket 的本 DAO）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/PointsMapper.xml。
 */
@Mapper
public interface IPointsDao {

    /** 撞 uk_user_reason_ref 返回 0 = 已入过账（幂等） */
    int insertIgnoreBucket(PointsBucketPO bucketPO);

    /** 当前可用桶，FIFO（expire_at, id）加行锁 */
    List<PointsBucketPO> selectAvailableBucketsForUpdate(@Param("userId") String userId);

    /**
     * 把「本次被扣光」的桶一次性清零（{@code remain = 0}），一条语句写完整笔方案。
     *
     * @return 命中并清零的行数（必须等于 {@code bucketIds} 条数，否则余量与方案不一致）
     */
    int drainBuckets(@Param("userId") String userId, @Param("bucketIds") List<String> bucketIds);

    /** 扣桶余量（方案里唯一那张部分扣的桶；带 remain >= amount 守卫，正常路径必中 1 行） */
    int deductRemain(@Param("bucketId") String bucketId, @Param("amount") int amount);

    int sumAvailableRemain(@Param("userId") String userId);

    List<PointsTransactionPO> selectTransactions(@Param("userId") String userId,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    /**
     * 桶合并候选用户批（points-compaction 票）：按用户聚合「有效小额桶」（remain > 0、未过期、
     * remain &le; 阈值），HAVING 数量 &ge; 最少桶数。普通快照读不加锁，加锁重读归
     * {@link #selectSmallBucketsForUpdate}。
     */
    List<PointsMergeGroupPO> selectMergeCandidateGroups(@Param("limit") int limit);

    /** 桶合并的加锁重读：单用户全部有效小额桶，FIFO（expire_at, id）加行锁 */
    List<PointsBucketPO> selectSmallBucketsForUpdate(@Param("userId") String userId);

    long countTransactions(@Param("userId") String userId);
}
