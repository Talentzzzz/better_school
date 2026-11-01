package com.betterschool.trigger.job;

import com.betterschool.domain.community.service.comment.ICommunityCommentService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 根评论热评索引全量重建（XXL-Job 手动触发，comment-count-narrow-table 票 03；照帖子
 * {@link FeedIndexRebuildJob} / 市集 {@code MarketFeedIndexRebuildJob} 样板）：
 * 兜底恢复手段——点赞旁带 {@code updateFeedIndexLikeCount} 命中 0 行的 warn、索引行被绕过直改、
 * 软删漏删索引行等漂移时，清空重灌 {@code post_comment_feed_index}（数据源 = NORMAL 根评论 + 计数窄表）。
 *
 * <p>只兜索引、不兜窄表：{@code post_comment_like_count} 是计数真值源，重建不改写它。
 *
 * <p>独立成类而不是把 handler 塞进 {@link FeedIndexRebuildJob}：两者重建的是不同表、不同聚合上的索引
 * （帖子 feed 索引 vs 根评论热评索引），而 {@code FeedIndexRebuildJob} 的类注释与 handler 名都已是帖子口吻；
 * 市集把两域合进一个类，是因为同一张业务页面的两个 tab 共用一套重建语句，评论这边不成立。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentFeedIndexRebuildJob {

    private final ICommunityCommentService communityCommentService;

    @XxlJob("commentFeedIndexRebuildJob")
    public String rebuild(String param) {
        communityCommentService.rebuildCommentFeedIndex();
        return "success";
    }
}
