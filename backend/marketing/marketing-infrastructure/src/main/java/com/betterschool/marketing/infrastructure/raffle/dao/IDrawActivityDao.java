package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawActivityPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 抽奖活动表（draw_activity）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawActivityMapper.xml。
 */
@Mapper
public interface IDrawActivityDao {

    DrawActivityPO selectActivityByActivityId(@Param("activityId") String activityId);

    /** 当前活动 = draw_activity 最新一行（单活动运营口径） */
    DrawActivityPO selectCurrentActivity();
}
