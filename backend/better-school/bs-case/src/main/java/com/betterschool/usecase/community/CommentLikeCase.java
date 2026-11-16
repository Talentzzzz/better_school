package com.betterschool.usecase.community;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.community.model.entity.PostCommentEntity;
import com.betterschool.domain.community.service.comment.ICommunityCommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 评论点赞编排（issue 06）：community 域点赞事务（唯一键幂等 + like_count 同事务 ±1）先提交，
 * chat 域通知尽力而为——失败只吞日志不回滚点赞（跨域写不共事务，项目约定）。
 * 按（sender + 目标评论）去重：同一人赞→取消→再赞只发一条；取消不撤回已发通知；自赞不发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentLikeCase {

    private final ICommunityCommentService communityCommentService;
    private final INotificationService notificationService;

    public void like(String commentId, String userId) {
        PostCommentEntity commentEntity = communityCommentService.likeComment(commentId, userId);
        if (commentEntity.getUserId().equals(userId)) {
            return; // 自赞不发
        }
        try {
            notificationService.publish(NotificationEventMessage.builder()
                    .notificationId(UUID.randomUUID().toString())
                    .userId(commentEntity.getUserId())
                    .senderId(userId)
                    .scope(NotificationScopeVO.INTERACTION_LIKE.name())
                    .type(NotificationTypeVO.COMMENT_LIKED.name())
                    .title("互动通知")
                    .content("你的评论收到了一个赞")
                    .refType("COMMENT")
                    .refId(commentId)
                    .dedupKey(NotificationTypeVO.COMMENT_LIKED.name() + ":" + commentId + ":" + userId)
                    .build());
        } catch (Exception e) {
            log.warn("[CommentLikeCase] 评论被赞通知发送失败（不回滚点赞） commentId={} senderId={}", commentId, userId, e);
        }
    }

    public void unlike(String commentId, String userId) {
        communityCommentService.unlikeComment(commentId, userId);
        // 取消赞不撤回已发通知
    }
}
