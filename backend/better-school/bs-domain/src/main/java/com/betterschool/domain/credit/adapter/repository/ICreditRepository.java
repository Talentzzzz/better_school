package com.betterschool.domain.credit.adapter.repository;

import com.betterschool.domain.credit.model.entity.CreditDetailEntity;
import com.betterschool.domain.credit.model.valobj.CreditChangeResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 信用分仓储：直操 bs_user（credit_score/state 列）、user_credit_detail 流水，
 * 全部条件 UPDATE / INSERT IGNORE；每个变更动作由仓储方法自身用事务（REQUIRED）收成一个原子用例
 */
public interface ICreditRepository {

    String getStatus(String userId);

    int getCreditScore(String userId);

    /** 条件增减分（DB clamp 0-100） */
    void updateCreditScore(String userId, int delta);

    /** 流水先行插入（uk 幂等），返回是否本次插入成功（false = 已处理过） */
    boolean insertCreditDetail(CreditDetailEntity creditDetailEntity);

    /** 当日该类变动累计（每日封顶判断用） */
    int sumTodayChange(String userId, String reasonType, LocalDate today);

    /** 低分联动封禁：仅 NORMAL 且分数 <30 时置 BANNED，返回是否本次刚封禁 */
    boolean banIfLowScore(String userId);

    List<CreditDetailEntity> pageList(String userId, int offset, int limit);

    long countDetails(String userId);

    /** 封禁/解封（条件 UPDATE），返回是否生效 */
    boolean updateStatus(String userId, String status);

    // ---------- 原子用例：仓储方法即一次事务（REQUIRED，与既有域事务合流） ----------

    /** 流水先行 + 调分 + 低分封禁 + 回读；流水撞 uk（已处理过）返回 null（幂等跳过） */
    CreditChangeResult applyChangeWithDetail(CreditDetailEntity creditDetailEntity);

    /** 管理员调分：流水（无 ref 不受 uk 约束）+ 调分 + 负分时低分封禁 + 回读；delta 取自流水实体 */
    CreditChangeResult adjustByAdminWithDetail(CreditDetailEntity creditDetailEntity);

    /** 管理员封禁：条件 UPDATE 0 行时已处于 BANNED = 幂等成功，否则抛 BUSINESS_ERROR；生效后落 0 分流水 */
    void banByAdminWithDetail(CreditDetailEntity creditDetailEntity);

    /** 管理员解封：条件 UPDATE 0 行时已处于 NORMAL = 幂等成功，否则抛 BUSINESS_ERROR；生效后落 0 分流水 */
    void unbanByAdminWithDetail(CreditDetailEntity creditDetailEntity);
}
