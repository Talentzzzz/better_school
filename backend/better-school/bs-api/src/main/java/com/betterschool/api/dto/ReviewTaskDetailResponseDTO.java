package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核工单详情聚合（spec §5.1：一屏拿到裁决所需全部上下文）：
 * 通用列（内容/配图/作者/进入待审时刻/审核轮次）+ 类型专属上下文（跑腿悬赏、评论的所属帖子/父评论）
 * + 同一内容历轮裁决 + 同一作者历史裁决；纯只读，不改变任何业务行对外可见性
 *
 * <p>⚠️ 共享类型手抄互指（票 12，spec §5.4）：本 DTO 是唯一事实源，
 * 前端手抄方 {@code front-review/src/types/review.ts} 的 ReviewTaskDetail；改这里必须同步。
 */
@Data
public class ReviewTaskDetailResponseDTO {

    /** 工单业务 ID（UUID 字符串，uuid-biz-id 票 06） */
    private String taskId;
    private String targetType;
    private String targetId;
    /** 审核轮次（COMMENT/ERRAND 恒 1；POST/PRODUCT 重提递增） */
    private Integer reviewRound;
    /** 工单态 PENDING(在池)/CLAIMED(租约中)/DONE(已裁决) */
    private String claimState;
    /** 进入待审时刻（FIFO 排序锚点；回收不改，等待时长继续累加） */
    private LocalDateTime enteredAt;
    /** 裁决时刻（DONE 时有值） */
    private LocalDateTime decidedAt;
    private String authorId;
    private String authorNickname;
    private String authorAvatar;
    /** 内容全文（帖=标题+正文，商=标题+描述，评=评论文本，跑腿=描述） */
    private String content;
    private List<String> images;
    /** 跑腿悬赏 / 商品价格（沿用审核队列 reward 列口径；帖/评为空） */
    private BigDecimal reward;
    /** 业务行当前状态（只读展示） */
    private String status;
    // ---- 评论类型专属上下文（其余类型为 null） ----
    /** 所属帖子业务 ID */
    private String contextPostId;
    /** 所属帖子标题 */
    private String contextPostTitle;
    /** 父评论业务 ID（根评论为 null） */
    private String contextParentId;
    /** 父评论文本（根评论为 null） */
    private String contextParentContent;
    /** 同一内容历轮裁决（review_round 升序） */
    private List<ReviewHistoryItemResponseDTO> targetReviews;
    /** 同一作者历史裁决（单表 author_id 直查，id 降序，最新 20 条） */
    private List<ReviewHistoryItemResponseDTO> authorReviews;
}
