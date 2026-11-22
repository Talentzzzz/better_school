package com.betterschool.infrastructure.dao.raffle.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖次数有效期配置行（draw_count_config，单行 id=1） */
@Data
public class DrawCountConfigPO {

    private Long id;
    private Integer drawCountValidDays;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
