package com.betterschool.infrastructure.dao.wallet;

import com.betterschool.infrastructure.dao.wallet.po.PaymentOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 支付单 Mapper（{@code payment_order} 单表）：模拟微信支付的统一下单 / 支付 / 关闭。
 * SQL 定义在 app 模块 {@code resources/mybatis/mapper/PaymentOrderMapper.xml}。
 */
@Mapper
public interface IPaymentOrderDao {

    /** 预下单（INSERT IGNORE）：uk_biz_order_id 冲突 = 该订单已有支付单，重复预下单安全 */
    int insertIgnore(PaymentOrderPO paymentOrderPO);

    PaymentOrderPO selectByPayNo(@Param("payNo") String payNo);

    /** 加锁读：并发支付的第二次条件更新失败后，用来确认支付单已被另一事务置 PAID */
    PaymentOrderPO selectByPayNoForUpdate(@Param("payNo") String payNo);

    PaymentOrderPO selectByBizOrderId(@Param("bizOrderId") String bizOrderId);

    /** WAIT_PAY → PAID（条件更新）；0 行 = 已支付 / 已关闭 / 并发抢先 */
    int markPaid(@Param("payNo") String payNo);

    /** WAIT_PAY → CLOSED（条件更新）；已支付 / 已关闭不受影响 */
    int closeByBizOrderId(@Param("bizOrderId") String bizOrderId);
}
