package com.betterschool.domain.raffle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发货状态机一次**推进**的结果（票 14）：{@code IRaffleShippingService.advanceShipAfterDraw} /
 * {@code confirmReceived} 的返回。
 * <p>
 * {@code advanced = false} 表示本次没有推进（幂等重放 / 已是终态 / 记录不存在）——重复投递的可观察形态。
 */
@Data
@AllArgsConstructor
public class ShippingResultVO {

    private String recordId;
    /** 推进后的 draw_record.state；未推进时为 null */
    private String state;
    /** 是否发生了状态推进 */
    private boolean advanced;
}
