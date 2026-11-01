package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跑腿订单超时延迟消息载荷：发单时按时限延迟投递，消费方触发超时处理（退款 + 状态 + 扣信用分）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrandTimeoutMessage {

    private Long orderId;
}
