package com.betterschool.integration.post;

import com.betterschool.infrastructure.adapter.repository.post.PostRepository;
import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feed-index-uv 票 05：浏览历史接口与 feed 已读灰显——消费 {@code user_view_history} 明细的两个读能力。
 *
 * <p>断言只看外部可观察状态：HTTP 响应 + MySQL 数据。浏览走真实详情链路（fire-and-forget 合并写），
 * 断言前排空合并器（无收集窗口，只为兜底在途件）；历史的期望序取自库内明细序（viewed_at 是秒级 DATETIME，同秒并列由
 * post_id tie-break 收敛成确定序），不假设浏览时刻互异。
 */
@DisplayName("浏览历史倒序分页 / 失效帖过滤 / feed 已读灰显与浏览事件一致")
class ViewHistoryIntegrationTest extends PostApiTestBase {

    /** 等合并器排空用它，等不到即断言失败（无收集窗口，正常路径本已落库，这里只兜在途件） */
    private static final long DRAIN_MILLIS = 5_000;
    private static final int PAGE_SIZE = 10;
    private static final int POST_COUNT = 25;

    @Autowired
    private PostRepository flushHandler;

    @Test
    void 历史倒序与分页不重不漏且total精确() {
        Session author = registerAndLogin("13700005001", "历史作者");
        Session viewer = registerAndLogin("13700005002", "历史浏览者");
        for (int i = 0; i < POST_COUNT; i++) {
            String postId = publishPost(author, "历史夹具帖" + i);
            assertSuccess(getJson("/api/community/posts/" + postId, viewer), "浏览详情");
        }
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("合并器必须已排空").isTrue();

        List<String> expected = jdbcTemplate.queryForList(
                "SELECT post_id FROM user_view_history WHERE user_id = ? "
                        + "ORDER BY viewed_at DESC, post_id DESC", String.class, viewer.userId());
        assertThat(expected).hasSize(POST_COUNT);

        List<String> walked = new ArrayList<>();
        long firstTotal = -1;
        int page = 1;
        while (true) {
            JsonNode data = getJson("/api/community/posts/history?page=" + page + "&pageSize=" + PAGE_SIZE, viewer)
                    .path("data");
            if (page == 1) {
                firstTotal = data.path("total").asLong();
            }
            List<String> ids = new ArrayList<>();
            data.path("list").forEach(node -> ids.add(node.path("postId").asText()));
            walked.addAll(ids);
            if (ids.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        assertThat(walked).as("倒序翻页不重不漏、整序与库内明细序一致").containsExactlyElementsOf(expected);
        // 25 条 / pageSize 10：10 + 10 + 5，第三页不满页自然收尾
        assertThat(walked).hasSize(POST_COUNT);
        assertThat(firstTotal).as("total 精确（走 JOIN 计数，不走 feed 的 -1 降口径）").isEqualTo(POST_COUNT);
    }

    @Test
    void 匿名与无历史用户均为空态() {
        Session fresh = registerAndLogin("13700005003", "无历史用户");

        // GET /api/community/** 在 Security 白名单内：匿名可达但一律空页（个人数据不外露）
        JsonNode anonymous = getJson("/api/community/posts/history?page=1&pageSize=" + PAGE_SIZE, null);
        assertSuccess(anonymous, "匿名查浏览历史");
        assertThat(anonymous.path("data").path("list")).as("匿名空页").isEmpty();
        assertThat(anonymous.path("data").path("total").asLong()).isZero();

        JsonNode mine = getJson("/api/community/posts/history?page=1&pageSize=" + PAGE_SIZE, fresh).path("data");
        assertThat(mine.path("list")).as("无历史用户空页").isEmpty();
        assertThat(mine.path("total").asLong()).isZero();
    }

    @Test
    void 已删的他人帖过滤本人帖照常展示() {
        Session author = registerAndLogin("13700005004", "过滤作者");
        Session viewer = registerAndLogin("13700005005", "过滤浏览者");
        String kept = publishPost(author, "保留的帖子");
        String removed = publishPost(author, "将被删除的帖子");
        // 本人帖发布即可见：作者视角详情可见，浏览要能落明细
        JsonNode ownPublish = postJson("/api/community/posts",
                Map.of("title", "我自己的帖", "content", "自己的帖子"), viewer);
        assertSuccess(ownPublish, "发本人帖");
        String ownPost = ownPublish.path("data").path("postId").asText();

        assertSuccess(getJson("/api/community/posts/" + kept, viewer), "浏览保留帖");
        assertSuccess(getJson("/api/community/posts/" + removed, viewer), "浏览将删帖");
        assertSuccess(getJson("/api/community/posts/" + ownPost, viewer), "浏览本人帖");
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("合并器必须已排空").isTrue();

        assertSuccess(deleteJson("/api/community/posts/" + removed, author), "作者删帖");

        JsonNode data = getJson("/api/community/posts/history?page=1&pageSize=" + PAGE_SIZE, viewer).path("data");
        List<String> ids = new ArrayList<>();
        data.path("list").forEach(node -> ids.add(node.path("postId").asText()));
        assertThat(ids).as("已删的他人帖过滤（失效卡片的收敛口径：过滤不展示）").doesNotContain(removed);
        assertThat(ids).as("仍可见帖与本人帖照常展示").containsExactlyInAnyOrder(kept, ownPost);
        assertThat(data.path("total").asLong()).as("total 与过滤口径一致").isEqualTo(2);
    }

    @Test
    void feed已读标记与浏览事件一致() {
        Session author = registerAndLogin("13700005006", "灰显作者");
        Session viewer = registerAndLogin("13700005007", "灰显浏览者");
        List<String> posts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            posts.add(publishPost(author, "灰显夹具帖" + i));
        }
        // 只浏览前两帖：后一帖未浏览
        assertSuccess(getJson("/api/community/posts/" + posts.get(0), viewer), "浏览第一帖");
        assertSuccess(getJson("/api/community/posts/" + posts.get(1), viewer), "浏览第二帖");
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("合并器必须已排空").isTrue();

        JsonNode feed = getJson("/api/community/posts?sort=time&pageSize=10", viewer);
        assertSuccess(feed, "主页 feed");
        Map<String, Boolean> viewedOf = new LinkedHashMap<>();
        feed.path("data").path("list").forEach(node ->
                viewedOf.put(node.path("postId").asText(), node.path("viewed").asBoolean()));
        assertThat(viewedOf.get(posts.get(0))).as("浏览过的帖 viewed=true").isTrue();
        assertThat(viewedOf.get(posts.get(1))).as("浏览过的帖 viewed=true").isTrue();
        assertThat(viewedOf.get(posts.get(2))).as("未浏览的帖 viewed=false").isFalse();

        // 匿名 feed：不带个人标记，全部 viewed=false
        JsonNode anonymousFeed = getJson("/api/community/posts?sort=time&pageSize=10", null);
        assertSuccess(anonymousFeed, "匿名主页 feed");
        anonymousFeed.path("data").path("list").forEach(node ->
                assertThat(node.path("viewed").asBoolean()).as("匿名恒 false").isFalse());
    }
}
