package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跑腿订单状态推送事件（outbox → MQ → WS ORDER 帧，只推订单双方在线者）：
 * 轻事件只带定位与新状态，订单详情内容以拉取为准（收到帧后前端重拉详情）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrandOrderPushEvent {

    private String orderId;
    /** 迁移后的状态名（ErrandStatusVO.name()） */
    private String status;
}
