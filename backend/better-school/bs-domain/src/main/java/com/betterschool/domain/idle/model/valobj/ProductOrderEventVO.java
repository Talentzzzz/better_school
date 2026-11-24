package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 商品订单状态流转事件快照（同订单 id 最大的一条）：孤儿扫描的已支付取消判据的事实源 */
@Data
@AllArgsConstructor
public class ProductOrderEventVO {

    /** 事件类型（CREATED/PAID/DELIVERED/COMPLETED/CANCELLED，与仓储写入侧字面量同口径） */
    private String eventType;

    /** 迁移前状态（孤儿扫描判据：CANCELLED 事件 from_state = PAID = 已支付取消，需补解冻退款） */
    private String fromState;
}
