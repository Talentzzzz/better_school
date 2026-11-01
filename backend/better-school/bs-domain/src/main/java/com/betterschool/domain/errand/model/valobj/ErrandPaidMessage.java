package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跑腿订单支付成功消息载荷（topic bs-errand-paid）：支付事务内随状态转待审落 outbox，
 * 消费方发"进入人工审核"通知
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrandPaidMessage {

    /** 跑腿订单业务 ID（UUID 字符串，uuid-biz-id 票 05） */
    private String orderId;
}
