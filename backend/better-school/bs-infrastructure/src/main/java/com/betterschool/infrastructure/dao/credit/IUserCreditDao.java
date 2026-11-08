package com.betterschool.infrastructure.dao.credit;

import com.betterschool.infrastructure.dao.credit.po.CreditDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信用分流水表 Mapper（user_credit_detail 单表），
 * SQL 定义在 app 模块 resources/mybatis/mapper/UserCreditMapper.xml
 */
@Mapper
public interface IUserCreditDao {

    /** 返回影响行数：0 = 该变动已处理过（幂等跳过） */
    int insertIgnoreCreditDetail(CreditDetail creditDetail);

    Integer sumTodayChange(@Param("userId") String userId, @Param("reasonType") String reasonType,
                           @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    long countReasonToday(@Param("userId") String userId, @Param("reasonType") String reasonType,
                          @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<CreditDetail> selectPage(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countDetails(@Param("userId") String userId);

    /** 同 target 举报单是否已有 REPORT_CONFIRMED 流水（直查 user_credit_detail 子查询，一内容一罚判定） */
    int existsProcessedCreditLog(@Param("authorId") String authorId, @Param("targetType") String targetType,
                                 @Param("targetId") String targetId);
}
