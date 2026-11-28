package com.betterschool.trigger.listener;

import com.betterschool.types.common.CommentLikedMessage;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.post.IPostCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 评论点赞消费方（topic {@code bs-comment-liked}）：把「谁赞了哪条评论」这个事实翻成互动通知。
 * <p>
 * 生产端（评论点赞主链路）只发事实，自赞在发 MQ 前已拦掉；通知类型/范围/文案/去重键
 * 这些决策都在 {@code PostCase#notifyCommentLiked}。
 * 解析失败或字段缺失直接丢弃（重试也不会成功）；通知发布失败上抛，由 broker 重投本消息，
 * 通知落库按 dedupKey 幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.COMMENT_LIKED_TOPIC, consumerGroup = "bs-monolith-comment-liked-group")
public class CommentLikedConsumer implements RocketMQListener<String> {

    private final IPostCase postCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        CommentLikedMessage commentLikedMessage;
        try {
            commentLikedMessage = objectMapper.readValue(payload, CommentLikedMessage.class);
        } catch (Exception e) {
            log.error("[CommentLikedConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (commentLikedMessage.getCommentId() == null || commentLikedMessage.getLikerUserId() == null
                || commentLikedMessage.getAuthorId() == null) {
            log.error("[CommentLikedConsumer] 消息缺 commentId/likerUserId/authorId，丢弃 payload={}", payload);
            return;
        }
        // 不 catch：业务异常上抛给 broker 重试，通知落库按 dedupKey 幂等
        postCase.notifyCommentLiked(commentLikedMessage);
    }
}
