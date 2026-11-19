package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawCountBucketPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽奖次数桶表（draw_count_bucket）Mapper：一表一 DAO（明细表见 IDrawCountDetailDao）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawCountMapper.xml。
 */
@Mapper
public interface IDrawCountDao {

    /** 撞 uk_user_reason_ref 返回 0 = 已入过账（幂等） */
    int insertIgnoreBucket(DrawCountBucketPO bucketPO);

    /** 当前可用桶，FIFO（expire_at, id）加行锁 */
    List<DrawCountBucketPO> selectAvailableBucketsForUpdate(@Param("userId") String userId);

    /** 扣桶余量（带 remain >= 1 守卫，正常路径必中 1 行） */
    int deductRemain(@Param("bucketId") String bucketId);

    int sumAvailableRemain(@Param("userId") String userId);
}
