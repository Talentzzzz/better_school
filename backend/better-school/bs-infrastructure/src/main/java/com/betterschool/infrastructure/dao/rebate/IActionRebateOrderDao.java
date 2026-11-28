package com.betterschool.infrastructure.dao.rebate;

import com.betterschool.infrastructure.dao.rebate.po.ActionRebateOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 行为返利订单表（action_rebate_order）Mapper：一表一 DAO。
 * SQL 定义在 app 模块 resources/mybatis/mapper/rebate/ActionRebateOrderMapper.xml。
 * <p>
 * 一次领取按返利类型拆行（POINTS / DRAW_COUNT），所以按 ref / 周期查出来是列表。
 * {@link #selectByRef} / {@link #selectByUserActionPeriod} 是<b>加锁读</b>（{@code FOR UPDATE}），
 * 只允许在领取事务内使用：REPEATABLE READ 下普通读看不到并发事务提交的新行，撞唯一键后的重查
 * 必须走加锁读才能拿到最新已提交状态。事务外的排障读用 {@link #selectByRefNoLock}。
 */
@Mapper
public interface IActionRebateOrderDao {

    /** 落订单行：撞 uk_user_action_ref / uk_user_action_period / uk_order_id 抛 DuplicateKeyException */
    int insert(ActionRebateOrder actionRebateOrder);

    /** 按 (user_id, action_type, ref_id) 取订单行并加锁（领取事务内查重 / 撞键后重查） */
    List<ActionRebateOrder> selectByRef(@Param("userId") String userId,
                                        @Param("actionType") String actionType,
                                        @Param("refId") String refId);

    /** 按 (user_id, action_type, ref_id) 取订单行，普通读（事务外排障用） */
    List<ActionRebateOrder> selectByRefNoLock(@Param("userId") String userId,
                                              @Param("actionType") String actionType,
                                              @Param("refId") String refId);

    /** 按 (user_id, action_type, period_key) 取订单行并加锁（领取事务内日限判定 / 撞键后重查） */
    List<ActionRebateOrder> selectByUserActionPeriod(@Param("userId") String userId,
                                                     @Param("actionType") String actionType,
                                                     @Param("periodKey") String periodKey);

    /** 按 order_id 取订单行（普通读） */
    ActionRebateOrder selectByOrderId(@Param("orderId") String orderId);

    /** CLAIMED → GRANTED：条件 UPDATE，重复调用 0 行、无副作用 */
    int markGranted(@Param("orderId") String orderId);
}
