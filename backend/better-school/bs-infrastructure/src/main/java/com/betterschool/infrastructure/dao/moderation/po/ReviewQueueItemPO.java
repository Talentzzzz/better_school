package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 审核队列统一结果 PO：工单定位列由 review_task 单表直查，素材列（content/reward/images/status/createTime）
 * 与 authorNickname 由仓储按 target_id 集回查业务表 + bs_user 拼装（SQL 层零 JOIN，字段求同） */
@Data
public class ReviewQueueItemPO {

    private String targetType;
    private String targetId;
    /** 审核轮次（业务表行锚定，POST/COMMENT/ERRAND 恒为 1） */
    private Integer reviewRound;
    private String authorId;
    private String authorNickname;
    private String content;
    private BigDecimal reward;
    /** 配图 URL 列表（errand_order/post/product images 原始 JSON 串，评论为空） */
    private String images;
    private String status;
    private LocalDateTime createTime;
    /** 进入待审时刻（工单 FIFO 排序锚点；回收不改，等待时长继续累加） */
    private LocalDateTime enteredAt;
}
