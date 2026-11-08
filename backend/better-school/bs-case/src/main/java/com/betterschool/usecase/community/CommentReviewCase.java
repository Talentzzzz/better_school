package com.betterschool.usecase.community;

import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.community.model.entity.PostCommentEntity;
import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.community.service.comment.ICommunityCommentService;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 评论审核消费编排（ADR-0004 人工化）：MQ 消费方不再做任何裁决，降级为"通知审核员有新评论待审"。
 * 同步初筛已在发布路径拦截明显违规（命中不落库、不产生 outbox 消息），本消费方仅对落库待审的评论
 * 提醒 ADMIN+AUDITOR 人工处理；审核通过与拒绝由统一裁决用例（ReviewCase）执行。
 * 幂等：sendSystem 按 dedup_key（类型+评论+收件人）唯一，重复消费不重复提醒
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentReviewCase {

    private final ICommunityCommentService communityCommentService;
    private final IUserRepository userRepository;
    private final INotificationService notificationService;

    public void review(String commentId) {
        PostCommentEntity commentEntity = communityCommentService.getComment(commentId);
        // 幂等：评论已被软删或已离开待审态（重复消费/人工先处理）不再提醒
        if (commentEntity == null || commentEntity.getStatus() != PostStatusVO.PENDING_REVIEW) {
            log.info("[CommentReviewCase] 评论不在待审态，跳过 commentId={} status={}",
                    commentId, commentEntity == null ? "null" : commentEntity.getStatus());
            return;
        }
        notifyReviewers(commentEntity);
    }

    /** 给 ADMIN+AUDITOR 各发一条"待审提醒"；逐收件人吞异常，通知失败不影响消费主流程（审核队列为准） */
    private void notifyReviewers(PostCommentEntity commentEntity) {
        List<String> reviewerIds = List.of(
                        userRepository.listUserIdsByRole(UserRoleVO.ADMIN),
                        userRepository.listUserIdsByRole(UserRoleVO.AUDITOR))
                .stream().flatMap(List::stream).distinct().toList();
        for (String reviewerId : reviewerIds) {
            try {
                notificationService.sendSystem(reviewerId, NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_PENDING, "审核提醒",
                        "评论 " + commentEntity.getCommentId() + " 待人工审核，请及时处理", "COMMENT", commentEntity.getCommentId(),
                        NotificationTypeVO.AUDIT_PENDING.name() + ":COMMENT:" + commentEntity.getCommentId() + ":" + reviewerId);
            } catch (Exception e) {
                log.warn("[CommentReviewCase] 待审提醒发送失败（MQ 重试不重复，下一条评论有提醒兜底） commentId={} reviewerId={}",
                        commentEntity.getCommentId(), reviewerId, e);
            }
        }
    }
}
