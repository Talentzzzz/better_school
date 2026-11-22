package com.betterschool.infrastructure.dao.raffle.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 兑换动作明细行（draw_record_detail 单表批量回查，type=EXCHANGE）：我的奖品 claimedAt 拼装用 */
@Data
public class ExchangeDetailRowPO {

    private String recordId;
    /** 兑换时刻（claimedAt 真源） */
    private LocalDateTime createTime;
}
