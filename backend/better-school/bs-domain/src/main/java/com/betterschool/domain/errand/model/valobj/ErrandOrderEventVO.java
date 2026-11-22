package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 订单状态流转事件快照（同订单 event_seq 最大的一条）：取消重试幂等判定的事实源 */
@Data
@AllArgsConstructor
public class ErrandOrderEventVO {

    /** 事件序号（业务顺序）：同订单内递增即状态变更顺序 */
    private int eventSeq;

    /** 事件类型（CREATED/LOCKED/CONFIRMED/TAKER_CANCELLED/…，与仓储写入侧字面量同口径） */
    private String eventType;

    /** 操作人用户 ID（SYSTEM 事件为 null） */
    private String actorId;

    /** 迁移前状态（孤儿扫描的 committed 判据：TIMEOUT 事件 from_state ∈ {CONFIRMED, PICKED_UP} = 已开工违约需扣分） */
    private String fromState;
}
