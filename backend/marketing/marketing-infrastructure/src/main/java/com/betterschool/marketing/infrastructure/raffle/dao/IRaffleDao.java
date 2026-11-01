package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawActivityPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawPrizeViewPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordDetailPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordViewPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyAwardPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyWeightPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawUserPrizeViewPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽奖域（draw_* 新模型）Mapper：决策侧四张配置表 + 抽奖记录两表 + 读侧视图查询，
 * 与 points / drawcount 同构（一域一 DAO）。SQL 定义在 marketing-app 模块
 * resources/mybatis/mapper/raffle/RaffleMapper.xml。
 */
@Mapper
public interface IRaffleDao {

    DrawActivityPO selectActivityByActivityId(@Param("activityId") Long activityId);

    DrawStrategyPO selectStrategyByStrategyId(@Param("strategyId") Long strategyId);

    List<DrawStrategyAwardPO> selectAwardListByStrategyId(@Param("strategyId") Long strategyId);

    /** 权重档按 count 升序（档命中取「count ≤ 累计抽数」里最大的一档） */
    List<DrawStrategyWeightPO> selectWeightListByStrategyId(@Param("strategyId") Long strategyId);

    /** 特型奖品位（type = normal/fallback/blacklist），取 sort,id 最小的一行 */
    Long selectAwardIdBySlot(@Param("strategyId") Long strategyId, @Param("type") String type);

    /** 用户在该策略下的累计抽数 = draw_record 行数（权重档命中判据） */
    int countUserRecords(@Param("strategyId") Long strategyId, @Param("userId") String userId);

    int insertDrawRecord(DrawRecordPO recordPO);

    /** 撞 uk_record_type 返回 0 = 该记录该动作已落过（state 推进重试幂等） */
    int insertIgnoreRecordDetail(DrawRecordDetailPO detailPO);

    // ---- 读侧（票 13）----

    /** 当前活动 = draw_activity 最新一行（单活动运营口径） */
    DrawActivityPO selectCurrentActivity();

    /** 按记录业务 ID 查（主链路幂等重放 / 并发去重） */
    DrawRecordPO selectRecordByRecordId(@Param("recordId") Long recordId);

    /** 积分面额：virtual_inventory_quota.value（仅 type=POINTS 行） */
    Integer selectPointsValueByInvId(@Param("invId") Long invId);

    /** 今日已抽数 = draw_record 该用户当日行数 */
    int countTodayDrawn(@Param("userId") String userId);

    /** 奖品目录视图：奖品位 LEFT JOIN 两段库存台账（remaining/total 展示口径） */
    List<DrawPrizeViewPO> selectPrizeCatalog(@Param("strategyId") Long strategyId);

    /** 我的中奖记录（恒过滤 THANKS，create_time DESC） */
    int countDrawRecords(@Param("userId") String userId);

    List<DrawRecordViewPO> selectDrawRecordViews(@Param("userId") String userId,
                                                 @Param("offset") int offset, @Param("limit") int limit);

    /** 我的奖品（award_type != THANKS，states 空 = 全部） */
    int countMyPrizes(@Param("userId") String userId, @Param("states") List<String> states);

    List<DrawUserPrizeViewPO> selectMyPrizeViews(@Param("userId") String userId,
                                                 @Param("states") List<String> states,
                                                 @Param("offset") int offset, @Param("limit") int limit);

    // ---- 发货状态机写面（票 14）----

    /**
     * 条件推进 draw_record.state（守卫 = 起点状态）：返回 0 = 状态已不在起点
     * （重复投递 / 并发重放 / 已终态），调用方据此判定「本次没有推进」。
     */
    int updateRecordState(@Param("recordId") Long recordId, @Param("fromState") String fromState,
                          @Param("toState") String toState);
}
