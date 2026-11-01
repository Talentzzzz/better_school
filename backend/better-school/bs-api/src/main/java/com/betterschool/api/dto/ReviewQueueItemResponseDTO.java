package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 审核队列条目（业务表 status=PENDING_REVIEW，按 targetType 分 tab 查询） */
@Data
public class ReviewQueueItemResponseDTO {

    /** 审核对象 POST/COMMENT/ERRAND */
    private String targetType;
    private String targetId;
    private String authorId;
    private String authorNickname;
    /** 审核内容全文（列表展示由前端截断） */
    private String content;
    /** 跑腿订单悬赏（帖子/评论为空） */
    private BigDecimal reward;
    /** 配图 URL 列表（跑腿订单，帖子/评论为空） */
    private List<String> images;
    /** 业务表当前状态 */
    private String status;
    private LocalDateTime createdAt;
    /** 进入待审时刻（工单 FIFO 排序锚点；回收不改，等待时长继续累加） */
    private LocalDateTime enteredAt;
}
