package com.betterschool.infrastructure.dao.draw.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 孤儿预扣对账单行（draw_orphan_prereserve_order）：种单扫描 + 重试状态机 + 死信 */
@Data
public class DrawOrphanPrereserveOrderPO {

    private Long id;
    private String recordId;
    private String awardId;
    private String userId;
    private Long quantity;
    private Integer state;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
