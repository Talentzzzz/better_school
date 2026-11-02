package com.betterschool.marketing.infrastructure.points.dao;

import com.betterschool.marketing.infrastructure.points.dao.po.PointsBucketPO;
import com.betterschool.marketing.infrastructure.points.dao.po.PointsDetailPO;
import com.betterschool.marketing.infrastructure.points.dao.po.PointsTransactionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 积分 Mapper（桶 + 消费明细，形状照单体 IWalletDao：一域一 DAO）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/PointsMapper.xml。
 */
@Mapper
public interface IPointsDao {

    /** 撞 uk_user_reason_ref 返回 0 = 已入过账（幂等） */
    int insertIgnoreBucket(PointsBucketPO bucketPO);

    /** 该用户该笔业务的消费行 id（不存在返回 null）；幂等预检用，普通快照读、不加锁 */
    Long selectConsumeId(@Param("userId") String userId,
                         @Param("reasonType") String reasonType,
                         @Param("refId") String refId);

    /** 当前可用桶，FIFO（expire_at, id）加行锁 */
    List<PointsBucketPO> selectAvailableBucketsForUpdate(@Param("userId") String userId);

    /**
     * 一条多值 {@code INSERT IGNORE} 写下整笔消费的全部明细（一次消费一条语句，行数 = 被碰到的桶数）。
     * 撞 {@code uk_bucket_reason_ref} 的行被跳过，表现为返回行数小于传入条数——调用方据此整笔回滚。
     * <p>
     * 列表不能为空：方案至少有一条扣减（空列表会拼出 {@code VALUES} 后无值的 SQL）。
     *
     * @return 实际插入的行数
     */
    int insertIgnoreDetails(@Param("details") List<PointsDetailPO> details);

    /**
     * 把「本次被扣光」的桶一次性清零（{@code remain = 0}），一条语句写完整笔方案。
     *
     * @return 命中并清零的行数（必须等于 {@code bucketIds} 条数，否则余量与方案不一致）
     */
    int drainBuckets(@Param("userId") String userId, @Param("bucketIds") List<String> bucketIds);

    /** 扣桶余量（方案里唯一那张部分扣的桶；带 remain >= amount 守卫，正常路径必中 1 行） */
    int deductRemain(@Param("bucketId") String bucketId, @Param("amount") int amount);

    int sumAvailableRemain(@Param("userId") String userId);

    /** 过期桶置零（不删行、留审计），返回清理行数 */
    int expireBuckets();

    List<PointsTransactionPO> selectTransactions(@Param("userId") String userId,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    long countTransactions(@Param("userId") String userId);
}
