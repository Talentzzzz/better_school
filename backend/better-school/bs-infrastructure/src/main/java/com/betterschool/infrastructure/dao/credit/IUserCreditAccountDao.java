package com.betterschool.infrastructure.dao.credit;

import com.betterschool.infrastructure.dao.credit.po.UserCredit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 信用账户 Mapper（user_credit 单表，credit 域独占写），
 * SQL 定义在 app 模块 resources/mybatis/mapper/UserCreditAccountMapper.xml。
 * 行懒创建：无行 = 默认分 100 / 状态 NORMAL；首次变更前先 seedIfAbsent 播种。
 */
@Mapper
public interface IUserCreditAccountDao {

    /** 播种默认行（INSERT IGNORE 撞 uk_user_id 忽略），保证后续条件 UPDATE 有行可命中 */
    int seedIfAbsent(@Param("userId") String userId);

    UserCredit selectByUserId(@Param("userId") String userId);

    /** 批量读（user 域实体拼装状态用，读跨域） */
    List<UserCredit> selectByUserIds(@Param("ids") List<String> userIdList);

    Integer selectCreditScore(@Param("userId") String userId);

    String selectState(@Param("userId") String userId);

    int updateCreditScore(@Param("userId") String userId, @Param("delta") int delta);

    /** 条件 UPDATE：仅 NORMAL 且分数 <30 时置 BANNED，返回影响行数：1 = 本次刚封禁（幂等依据） */
    int banIfLowScore(@Param("userId") String userId);

    /** 封禁/解封通用（条件 state <> 目标值），返回影响行数 */
    int updateStatus(@Param("userId") String userId, @Param("state") String state);
}
