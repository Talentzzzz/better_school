package com.betterschool.infrastructure.dao.credit;

import com.betterschool.infrastructure.dao.credit.po.CreditDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信用分 Mapper：直操 bs_user（credit_score/state 列）+ user_credit_detail 流水，
 * SQL 定义在 app 模块 resources/mybatis/mapper/CreditMapper.xml
 */
@Mapper
public interface ICreditDao {

    String selectStatus(@Param("userId") String userId);

    Integer selectCreditScore(@Param("userId") String userId);

    int updateCreditScore(@Param("userId") String userId, @Param("delta") int delta);

    /** 返回影响行数：0 = 该变动已处理过（幂等跳过） */
    int insertIgnoreCreditDetail(CreditDetailPO creditDetailPO);

    Integer sumTodayChange(@Param("userId") String userId, @Param("reasonType") String reasonType,
                           @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 返回影响行数：1 = 本次刚封禁（幂等依据） */
    int banIfLowScore(@Param("userId") String userId);

    long countReasonToday(@Param("userId") String userId, @Param("reasonType") String reasonType,
                          @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<CreditDetailPO> selectPage(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countDetails(@Param("userId") String userId);

    /** 封禁/解封通用（条件 state <> 目标值），返回影响行数 */
    int updateStatus(@Param("userId") String userId, @Param("state") String state);
}
