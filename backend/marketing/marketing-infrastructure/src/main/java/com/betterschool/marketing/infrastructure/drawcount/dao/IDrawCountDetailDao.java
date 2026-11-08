package com.betterschool.marketing.infrastructure.drawcount.dao;

import com.betterschool.marketing.infrastructure.drawcount.dao.po.DrawCountDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 抽奖次数消费明细表（draw_count_detail）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/DrawCountDetailMapper.xml。
 */
@Mapper
public interface IDrawCountDetailDao {

    /** 加锁读该抽奖记录的消费明细行 id（不存在返回 null）；幂等预检用 */
    Long selectDetailIdForUpdate(@Param("refId") String refId);

    /** 撞 uk_bucket_ref 返回 0 = 该桶这一笔抽奖已扣过 */
    int insertIgnoreDetail(DrawCountDetailPO detailPO);
}
