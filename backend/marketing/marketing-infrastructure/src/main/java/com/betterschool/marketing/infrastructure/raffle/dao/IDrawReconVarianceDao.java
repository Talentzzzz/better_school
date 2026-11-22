package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawReconVariancePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对账差错表（draw_recon_variance）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawReconVarianceMapper.xml。
 * <p>
 * 差错状态机 PENDING(0) → CONFIRMED(1) → AUTO_FIXED(2) / RESOLVED_MANUAL(3) / FALSE_ALARM(4)；
 * 全部状态推进都是按 id + 起点状态的条件更新，0 行 = 状态已漂移（并发推进 / 已终态），调用方跳过。
 */
@Mapper
public interface IDrawReconVarianceDao {

    /** 撞 uk_round_check_biz 返回 0 = 本轮该检查类型该业务单号已登记过（调度重复触发/重跑幂等） */
    int insertIgnoreVariance(DrawReconVariancePO variancePO);

    /** 全部轮次的 PENDING 差错（下一轮复查的二次核对输入，不分轮次——调度中断滞留的更早轮次也能收口） */
    List<DrawReconVariancePO> listAllPending();

    /** 二次核对确认：state 0→1；0 行 = 已不在 PENDING（复查时已消失置 FALSE_ALARM 等），调用方跳过 */
    int confirm(@Param("id") Long id);

    /** 平账分流·自动修复：state 1→2，写 fixed_time；0 行 = 已不在 CONFIRMED */
    int markAutoFixed(@Param("id") Long id);

    /** 复查消失：state 0→4，写 finish_time（日切边界假差错收口） */
    int markFalseAlarm(@Param("id") Long id);

    /** 该轮次登记差错总数（轮次表终轮回填 variance_total） */
    long countByRound(@Param("roundNo") String roundNo);

    /** 该轮次指定状态的差错数（终轮回填 confirmed_total / auto_fixed_total 等计数） */
    long countByRoundAndState(@Param("roundNo") String roundNo, @Param("state") int state);
}
