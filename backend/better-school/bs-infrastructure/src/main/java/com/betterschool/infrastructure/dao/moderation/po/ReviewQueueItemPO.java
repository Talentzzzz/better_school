package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 审核队列跨表查询统一结果 PO（post/post_comment/errand_order/product 各查各的，字段求同） */
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
