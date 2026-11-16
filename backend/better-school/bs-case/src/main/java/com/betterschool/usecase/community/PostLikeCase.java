package com.betterschool.usecase.community;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.service.post.ICommunityPostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 帖子点赞编排（issue 06）：community 域点赞事务先提交，chat 域通知尽力而为——失败只吞日志不回滚点赞
 * （跨域写不共事务，项目约定）。按（sender + 目标帖子）去重：同一人赞→取消→再赞只发一条；
 * 取消不撤回已发通知；自赞不发。取消赞无通知动作，直接透传域服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostLikeCase {

    private final ICommunityPostService communityPostService;
    private final INotificationService notificationService;

    public void like(String postId, String userId) {
        communityPostService.likePost(postId, userId);
        PostEntity postEntity = communityPostService.getPost(postId);
        if (postEntity == null || postEntity.getUserId().equals(userId)) {
            return; // 自赞不发
        }
        try {
            notificationService.publish(NotificationEventMessage.builder()
                    .notificationId(UUID.randomUUID().toString())
                    .userId(postEntity.getUserId())
                    .senderId(userId)
                    .scope(NotificationScopeVO.INTERACTION_LIKE.name())
                    .type(NotificationTypeVO.POST_LIKED.name())
                    .title("互动通知")
                    .content("你的帖子收到了一个赞")
                    .refType("POST")
                    .refId(postId)
                    .dedupKey(NotificationTypeVO.POST_LIKED.name() + ":" + postId + ":" + userId)
                    .build());
        } catch (Exception e) {
            log.warn("[PostLikeCase] 帖子被赞通知发送失败（不回滚点赞） postId={} senderId={}", postId, userId, e);
        }
    }
}
