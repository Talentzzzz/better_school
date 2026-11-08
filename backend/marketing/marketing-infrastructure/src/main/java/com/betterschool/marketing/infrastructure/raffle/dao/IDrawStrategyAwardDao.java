package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyAwardPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽奖奖品表（draw_strategy_award）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawStrategyAwardMapper.xml。
 */
@Mapper
public interface IDrawStrategyAwardDao {

    List<DrawStrategyAwardPO> selectAwardListByStrategyId(@Param("strategyId") String strategyId);

    /** 特型奖品位（type = normal/fallback/blacklist），取 sort,id 最小的一行 */
    String selectAwardIdBySlot(@Param("strategyId") String strategyId, @Param("type") String type);
}
