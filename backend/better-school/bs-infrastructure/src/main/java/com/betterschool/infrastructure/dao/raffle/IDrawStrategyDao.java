package com.betterschool.infrastructure.dao.raffle;

import com.betterschool.infrastructure.dao.raffle.po.DrawStrategyPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 抽奖策略表（draw_strategy）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawStrategyMapper.xml。
 */
@Mapper
public interface IDrawStrategyDao {

    DrawStrategyPO selectStrategyByStrategyId(@Param("strategyId") String strategyId);
}
