package com.betterschool.marketing.infrastructure.drawcount.dao;

import com.betterschool.marketing.infrastructure.drawcount.dao.po.DrawCountBucketPO;
import com.betterschool.marketing.infrastructure.drawcount.dao.po.DrawCountDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽奖次数 Mapper（桶 + 消费明细，与积分侧 IPointsDao 同构：一域一 DAO）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/DrawCountMapper.xml。
 */
@Mapper
public interface IDrawCountDao {

    /** 撞 uk_user_reason_ref 返回 0 = 已入过账（幂等） */
    int insertIgnoreBucket(DrawCountBucketPO bucketPO);

    /** 加锁读该抽奖记录的消费明细行 id（不存在返回 null）；幂等预检用 */
    Long selectDetailIdForUpdate(@Param("refId") String refId);

    /** 当前可用桶，FIFO（expire_at, id）加行锁 */
    List<DrawCountBucketPO> selectAvailableBucketsForUpdate(@Param("userId") String userId);

    /** 撞 uk_bucket_ref 返回 0 = 该桶这一笔抽奖已扣过 */
    int insertIgnoreDetail(DrawCountDetailPO detailPO);

    /** 扣桶余量（带 remain >= 1 守卫，正常路径必中 1 行） */
    int deductRemain(@Param("bucketId") String bucketId);

    int sumAvailableRemain(@Param("userId") String userId);

    /** 过期桶置零（不删行、留审计），返回清理行数 */
    int expireBuckets();
}
