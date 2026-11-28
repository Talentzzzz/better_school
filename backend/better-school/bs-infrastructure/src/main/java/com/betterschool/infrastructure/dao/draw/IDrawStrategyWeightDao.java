package com.betterschool.infrastructure.dao.draw;

import com.betterschool.infrastructure.dao.draw.po.DrawStrategyWeightPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽奖权重表（draw_strategy_weight）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/draw/DrawStrategyWeightMapper.xml。
 */
@Mapper
public interface IDrawStrategyWeightDao {

    /** 权重档按 count 升序（档命中取「count ≤ 累计抽数」里最大的一档） */
    List<DrawStrategyWeightPO> selectWeightListByStrategyId(@Param("strategyId") String strategyId);
}
