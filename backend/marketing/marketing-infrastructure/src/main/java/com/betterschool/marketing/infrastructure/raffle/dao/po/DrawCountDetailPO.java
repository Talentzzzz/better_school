package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖次数消费明细行（draw_count_detail；无 amount 列，一行 = 1 次） */
@Data
public class DrawCountDetailPO {

    private Long id;
    private String userId;
    private String bucketId;
    private String refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
