package com.betterschool.infrastructure.dao.rebate;

import com.betterschool.infrastructure.dao.rebate.po.ActionRebateClaim;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 行为返利领取记录表（action_rebate_claim）Mapper：一表一 DAO。
 * SQL 定义在 app 模块 resources/mybatis/mapper/rebate/ActionRebateClaimMapper.xml。
 * <p>
 * {@link #selectByRef} / {@link #selectByUserActionPeriod} 是<b>加锁读</b>（{@code FOR UPDATE}），
 * 只允许在领取事务内使用：REPEATABLE READ 下普通读看不到并发事务提交的新行，撞唯一键后的重查
 * 必须走加锁读才能拿到最新已提交状态。事务外的排障读用 {@link #selectByRefNoLock}。
 */
@Mapper
public interface IActionRebateClaimDao {

    /** 落 claim 行：撞 uk_user_action_ref / uk_user_action_period / uk_claim_id 抛 DuplicateKeyException */
    int insert(ActionRebateClaim actionRebateClaim);

    /** 按 (user_id, action_type, ref_id) 取领取行并加锁（领取事务内查重 / 撞键后重查） */
    ActionRebateClaim selectByRef(@Param("userId") String userId,
                                  @Param("actionType") String actionType,
                                  @Param("refId") String refId);

    /** 按 (user_id, action_type, ref_id) 取领取行，普通读（事务外排障用） */
    ActionRebateClaim selectByRefNoLock(@Param("userId") String userId,
                                        @Param("actionType") String actionType,
                                        @Param("refId") String refId);

    /** 按 (user_id, action_type, period_key) 取领取行并加锁（领取事务内日限判定 / 撞键后重查） */
    ActionRebateClaim selectByUserActionPeriod(@Param("userId") String userId,
                                               @Param("actionType") String actionType,
                                               @Param("periodKey") String periodKey);

    /** 按 claim_id 取领取行（普通读） */
    ActionRebateClaim selectByClaimId(@Param("claimId") String claimId);

    /** CLAIMED → GRANTED：条件 UPDATE，重复调用 0 行、无副作用 */
    int markGranted(@Param("claimId") String claimId);
}
