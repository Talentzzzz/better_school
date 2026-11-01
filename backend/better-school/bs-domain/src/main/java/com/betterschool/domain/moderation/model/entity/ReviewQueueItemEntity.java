package com.betterschool.domain.moderation.model.entity;

import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核队列条目（moderation 域只读直查业务表 status=PENDING_REVIEW 的统一结果）：
 * post / post_comment / errand_order / product 各查各的，字段求同
 */
@Data
public class ReviewQueueItemEntity {

    private ReviewTargetTypeVO targetType;
    private String targetId;
    /** 审核轮次（同一目标第 N 次进入待审，裁决记录按此落轮次） */
    private int reviewRound;
    private String authorId;
    private String authorNickname;
    /** 审核内容全文（列表/详情通用，api 层按需截断展示） */
    private String content;
    /** 跑腿订单悬赏（帖子/评论为空） */
    private BigDecimal reward;
    /** 配图 URL 列表（跑腿/帖子/商品有值，评论为空） */
    private List<String> images;
    /** 业务表当前状态（PENDING_REVIEW/REJECTED/NORMAL 等） */
    private String status;
    private LocalDateTime createdAt;
    /** 进入待审时刻（工单 FIFO 排序锚点；回收不改，等待时长继续累加） */
    private LocalDateTime enteredAt;
}
