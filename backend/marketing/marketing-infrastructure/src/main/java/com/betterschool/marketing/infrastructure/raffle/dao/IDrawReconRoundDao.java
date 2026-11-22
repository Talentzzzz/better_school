package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawReconRoundPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 对账轮次观测表（draw_recon_round）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawReconRoundMapper.xml。
 */
@Mapper
public interface IDrawReconRoundDao {

    /** 起轮：撞 uk_round_no 返回 0 = 本轮已起过（调度重复触发按幂等跳过） */
    int insertIgnoreRound(DrawReconRoundPO roundPO);

    /** 终轮：回填结束时间与三项计数；按 round_no 定位（一轮一行，无状态机） */
    int finishRound(@Param("roundNo") String roundNo,
                    @Param("scanEndTime") LocalDateTime scanEndTime,
                    @Param("varianceTotal") int varianceTotal,
                    @Param("confirmedTotal") int confirmedTotal,
                    @Param("autoFixedTotal") int autoFixedTotal);
}
