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

    /** 奖品位行的投放池配置（inv_id；award-inventory 两层模型下它只作分配来源与「不限量」哨兵） */
    String selectInvIdByAwardId(@Param("awardId") String awardId);

    /** 按 award_id 集批量回查奖品位行（读侧 award_id → inv_id 的映射拼装源）；无匹配返回空集 */
    List<DrawStrategyAwardPO> selectAwardsByAwardIds(@Param("awardIds") List<String> awardIds);

    /** 奖品位是否存在（分配 usecase 的入参校验用） */
    boolean existsByAwardId(@Param("awardId") String awardId);
}
