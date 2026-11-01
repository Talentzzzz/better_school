package com.betterschool.trigger.listener;

import com.betterschool.domain.community.model.valobj.CommentReviewMessage;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.community.CommentReviewCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 评论待审通知消费方（ADR-0004，票 03）：发布链路 outbox 落消息 → 消费降级为"通知审核员有评论待审"（不再裁决）。
 * 幂等：CommentReviewCase 先判评论是否仍处待审态，重复消费时已离开待审态被静默跳过；提醒凭 dedup_key 去重
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.COMMENT_REVIEW_TOPIC, consumerGroup = "bs-comment-review-group")
public class CommentReviewConsumer implements RocketMQListener<String> {

    private final CommentReviewCase commentReviewCase;
    private final ObjectMapper objectMapper;

    /**
     * 反序列化失败等毒消息记日志吞掉（重试无意义）；业务处理失败向上抛交 broker 重试——
     * 审核是强一致语义（卡待审 = 永不可见不计数），不同于浏览计数的「允许丢」口径
     * （浏览走进程内合并，失败只记丢弃数，spec §3.2）
     */
    @Override
    public void onMessage(String payload) {
        CommentReviewMessage message;
        try {
            message = objectMapper.readValue(payload, CommentReviewMessage.class);
        } catch (Exception e) {
            log.error("[CommentReviewConsumer] 消息反序列化失败，放弃消费 payload={}", payload, e);
            return;
        }
        commentReviewCase.review(message.getCommentId());
        log.info("[CommentReviewConsumer] 评论审核处理完成 commentId={}", message.getCommentId());
    }
}
