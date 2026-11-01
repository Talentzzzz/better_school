package com.betterschool.domain.community.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评论审核消息（MQ 载荷契约，ADR-0002）：生产者（发布链路同事务 outbox）构造、
 * 消费方（trigger listener）反序列化后按 commentId 回查评论执行敏感词检查。
 * 幂等不靠消息：靠评论状态机条件 UPDATE（PENDING_REVIEW → NORMAL/REJECTED）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentReviewMessage {

    private String commentId;
}
