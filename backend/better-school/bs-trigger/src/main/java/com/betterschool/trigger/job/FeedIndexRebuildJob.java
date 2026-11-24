package com.betterschool.trigger.job;

import com.betterschool.domain.post.service.post.IPostService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * feed 索引全量重建（XXL-Job 手动触发）：
 * 兜底恢复手段——同事务直写丢更新 / 索引行与窄表漂移（updateFeedIndexLikeCount 命中 0 行的 warn）时，
 * 清空重灌 post_feed_index（数据源 = post + post_like_count + 审核记录）并把 post_view_count
 * 校准成 user_view_history 行数（feed-index-uv 票 07）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedIndexRebuildJob {

    private final IPostService postService;

    @XxlJob("feedIndexRebuildJob")
    public String rebuild(String param) {
        postService.rebuildFeedIndex();
        return "success";
    }
}
