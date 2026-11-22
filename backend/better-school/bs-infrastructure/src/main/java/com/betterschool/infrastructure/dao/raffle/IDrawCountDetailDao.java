package com.betterschool.infrastructure.dao.raffle;

import com.betterschool.infrastructure.dao.raffle.po.DrawCountDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 抽奖次数消费明细表（draw_count_detail）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawCountDetailMapper.xml。
 */
@Mapper
public interface IDrawCountDetailDao {

    /** 加锁读该抽奖记录的消费明细行 id（不存在返回 null）；幂等预检用 */
    Long selectDetailIdForUpdate(@Param("refId") String refId);

    /** 撞 uk_bucket_ref 返回 0 = 该桶这一笔抽奖已扣过 */
    int insertIgnoreDetail(DrawCountDetailPO detailPO);

    /**
     * 按抽奖记录反查扣次明细（走 idx_ref，无锁读）：孤儿预扣种单时取 user_id 快照——
     * 孤儿口径下扣次必然已发生（主链路先扣次后预扣），明细行不在 = 数据破坏，调用方跳过留 ERROR。
     */
    DrawCountDetailPO selectByRefId(@Param("refId") String refId);
}
