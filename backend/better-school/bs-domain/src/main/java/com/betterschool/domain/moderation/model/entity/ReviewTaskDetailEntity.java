package com.betterschool.domain.moderation.model.entity;

import lombok.Data;

import java.util.List;

/**
 * 工单详情聚合（spec §5.1：审核员点开一条工单一屏拿到裁决所需全部上下文）：
 * 工单定位与派单态 + 内容素材（通用列 + 类型专属上下文）
 * + 同一内容历轮裁决（content_review 按 target_type+target_id 查）
 * + 同一作者历史裁决（content_review 单表 author_id 直查，免 join 工单表）。
 * 纯只读聚合，不改变任何业务行状态（工单不承载可见性）
 */
@Data
public class ReviewTaskDetailEntity {

    /** 工单定位与派单态（claim_state/entered_at/decided_at 等） */
    private ReviewTaskEntity task;
    /** 内容素材：通用列 + 类型专属上下文 */
    private ReviewTargetDetailEntity target;
    /** 同一内容历轮裁决（按 review_round 升序；重提前后各轮各自的结论与拒绝理由） */
    private List<ContentReviewEntity> targetReviews;
    /** 同一作者历史裁决（单表 author_id 直查，id 降序，最新 N 条） */
    private List<ContentReviewEntity> authorReviews;
}
