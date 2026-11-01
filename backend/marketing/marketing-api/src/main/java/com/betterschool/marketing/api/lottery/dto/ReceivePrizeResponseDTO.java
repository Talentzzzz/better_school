package com.betterschool.marketing.api.lottery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 确认收货的结果（票 14 发货闭环）：`status` 是推进后的四段状态（恒 `COMPLETED`），
 * 前端据此把「我的奖品」那一行就地切到已完成，不必再打一次列表接口。
 */
@Data
@AllArgsConstructor
public class ReceivePrizeResponseDTO {

    /** 抽奖记录业务 ID（= draw_record.record_id，前端「我的奖品」行的 id） */
    private Long id;
    /** 推进后的状态 PENDING / EXCHANGED / SHIPPED / COMPLETED */
    private String status;
}
