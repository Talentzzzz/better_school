package com.betterschool.infrastructure.dao.draw;

import com.betterschool.infrastructure.dao.draw.po.DrawRecordPO;
import com.betterschool.infrastructure.dao.draw.po.DrawRecordViewPO;
import com.betterschool.infrastructure.dao.draw.po.DrawUserPrizeViewPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽奖记录表（draw_record）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/draw/DrawMapper.xml。
 */
@Mapper
public interface IDrawDao {

    /** 用户在该策略下的累计抽数 = draw_record 行数（权重档命中判据） */
    int countUserRecords(@Param("strategyId") String strategyId, @Param("userId") String userId);

    int insertDrawRecord(DrawRecordPO drawRecordPO);

    // ---- 读侧（票 13）----

    /** 按记录业务 ID 查（主链路幂等重放 / 并发去重） */
    DrawRecordPO selectRecordByRecordId(@Param("recordId") String recordId);

    /** 今日已抽数 = draw_record 该用户当日行数 */
    int countTodayDrawn(@Param("userId") String userId);

    /** 我的中奖记录（恒过滤 THANKS，create_time DESC） */
    int countDrawRecords(@Param("userId") String userId);

    /** 我的中奖记录主查：draw_record 单表分页（面额由仓储按 inv_id 集回查积分台账拼装，SQL 层零 JOIN） */
    List<DrawRecordViewPO> selectDrawRecordRows(@Param("userId") String userId,
                                                @Param("offset") int offset, @Param("limit") int limit);

    /** 我的奖品（award_type != THANKS，states 空 = 全部） */
    int countMyPrizes(@Param("userId") String userId, @Param("states") List<String> states);

    /** 我的奖品主查：draw_record 单表分页（claimedAt 由仓储按 record_id 集回查 EXCHANGE 明细拼装） */
    List<DrawUserPrizeViewPO> selectMyPrizeRows(@Param("userId") String userId,
                                                @Param("states") List<String> states,
                                                @Param("offset") int offset, @Param("limit") int limit);

    // ---- 发货状态机写面（票 14）----

    /**
     * 条件推进 draw_record.state（守卫 = 起点状态）：返回 0 = 状态已不在起点
     * （重复投递 / 并发重放 / 已终态），调用方据此判定「本次没有推进」。
     */
    int updateRecordState(@Param("recordId") String recordId, @Param("fromState") String fromState,
                          @Param("toState") String toState);
}
