package com.betterschool.integration.post;

import com.betterschool.domain.post.adapter.port.IUserQueryPort;
import com.betterschool.domain.post.adapter.repository.IPostRepository;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.domain.post.service.post.PostServiceImpl;
import com.betterschool.domain.post.model.valobj.FeedPageVO;
import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * feed-index-uv 票 04：主页 feed 读路径切换——post_feed_index 索引序扫描 + 游标分页。
 *
 * <p>断言只看外部可观察状态：HTTP 响应的 list / total / cursor。两排序 × 游标翻页的
 * 「不重不漏」以库内索引序（(feed_time, post_id) / (like_count, feed_time, post_id) DESC）为期望序——
 * feed_time 是秒级 DATETIME，夹具大量同秒通过，同秒并列由 post_id tie-break 收敛成确定序
 * （uuid-biz-id 票 03：post_id UUID 化后仍只做同值最终收敛，不再承载时间趋势）。
 * 降级路径走白盒（spy 仓储让索引扫描抛异常）——黑盒造不出索引表查询故障。
 */
@DisplayName("feed 读路径切换：索引序游标翻页不重不漏 / total 降口径 / 降级直查")
class FeedCursorPagingIntegrationTest extends PostApiTestBase {

    private static final int PAGE_SIZE = 10;
    private static final int POST_COUNT = 25;

    @Autowired
    private IPostRepository postRepository;
    @Autowired
    private IUserQueryPort userQueryPort;

    @Test
    void 最新排序游标翻页不重不漏且total降口径() {
        Session author = registerAndLogin("13900002401", "游标作者");
        List<String> seeded = seedNormalPosts(author, POST_COUNT);

        List<String> expected = jdbcTemplate.queryForList(
                "SELECT post_id FROM post_feed_index WHERE state = 'NORMAL' "
                        + "ORDER BY feed_time DESC, post_id DESC", String.class);
        assertThat(expected).hasSize(POST_COUNT);

        JsonNode first = fetchFeed(null, null);
        assertThat(first.path("total").asLong()).as("索引路径 total 降口径（不再精确 COUNT）").isEqualTo(-1);
        assertThat(first.path("page").asLong()).as("page/pageSize 兼容字段照常回显").isEqualTo(1);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            JsonNode data = fetchFeed(null, cursor);
            List<String> ids = postIdsOf(data);
            assertThat(ids).as("每页都不超过 pageSize").size().isLessThanOrEqualTo(PAGE_SIZE);
            walked.addAll(ids);
            pages++;
            cursor = data.path("cursor").asText(null);
            if (cursor == null) {
                break;
            }
        }
        assertThat(walked).as("游标翻页不重不漏、整序与索引序一致").containsExactlyElementsOf(expected);
        // 25 条 / pageSize 10：10 + 10 + 5，第三页不满页自然收尾（cursor 为 null）
        assertThat(pages).isEqualTo(3);
        assertThat(walked).containsAll(seeded);
    }

    @Test
    void 热榜排序游标翻页不重不漏() {
        Session author = registerAndLogin("13900002402", "热榜游标作者");
        List<String> seeded = seedNormalPosts(author, POST_COUNT);
        // 赞数分档 0/1/2/3：每档若干帖，同档内靠 post_id tie-break 收敛
        Session liker1 = registerAndLogin("13900002403", "游标点赞人一");
        Session liker2 = registerAndLogin("13900002404", "游标点赞人二");
        Session liker3 = registerAndLogin("13900002405", "游标点赞人三");
        for (int i = 0; i < seeded.size(); i++) {
            int tier = i % 4;
            for (int n = 0; n < tier; n++) {
                like(seeded.get(i), switch (n) {
                    case 0 -> liker1;
                    case 1 -> liker2;
                    default -> liker3;
                });
            }
        }

        List<String> expected = jdbcTemplate.queryForList(
                "SELECT post_id FROM post_feed_index WHERE state = 'NORMAL' "
                        + "ORDER BY like_count DESC, feed_time DESC, post_id DESC", String.class);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        while (true) {
            JsonNode data = fetchFeed("hot", cursor);
            List<String> ids = postIdsOf(data);
            assertThat(new HashSet<>(ids)).as("单页内无重复").hasSameSizeAs(ids);
            walked.addAll(ids);
            cursor = data.path("cursor").asText(null);
            if (cursor == null) {
                break;
            }
        }
        assertThat(walked).as("热榜游标翻页不重不漏").containsExactlyElementsOf(expected);
    }

    @Test
    void 游标锚点隔离跨页跳变() {
        Session author = registerAndLogin("13900002406", "跳变作者");
        List<String> seeded = seedNormalPosts(author, POST_COUNT);
        Session liker1 = registerAndLogin("13900002407", "跳变点赞人一");
        Session liker2 = registerAndLogin("13900002408", "跳变点赞人二");

        JsonNode firstPage = fetchFeed("hot", null);
        List<String> firstIds = postIdsOf(firstPage);
        // 把第一页锚点行（页尾）顶到榜首：点赞 +N 使其 like_count 超过锚点值
        String anchorPost = firstIds.get(firstIds.size() - 1);
        like(anchorPost, liker1);
        like(anchorPost, liker2);

        JsonNode secondPage = fetchFeed("hot", firstPage.path("cursor").asText());
        List<String> secondIds = postIdsOf(secondPage);
        assertThat(secondIds).as("已见过的锚点行不再出现在后续页（keyset 不重）").doesNotContain(anchorPost);
        assertThat(new HashSet<>(firstIds)).as("两页无交集").doesNotContainAnyElementsOf(secondIds);
        assertThat(seeded).containsAll(firstIds);
        assertThat(seeded).containsAll(secondIds);
    }

    @Test
    void 非法与排序不符的游标报参数错误() {
        Session author = registerAndLogin("13900002410", "游标校验作者");
        seedNormalPosts(author, POST_COUNT);

        assertThat(getJson("/api/community/posts?sort=time&cursor=garbage!!", null).path("code").asText())
                .as("Base64 解不开 → PARAM_ERROR").isEqualTo("400");
        // 同一游标换排序复用：解码出的 sort 与请求不符 → PARAM_ERROR
        String timeCursor = fetchFeed(null, null).path("cursor").asText();
        assertThat(timeCursor).as("夹具足够多，首页应发放续页游标").isNotNull();
        assertThat(getJson("/api/community/posts?sort=hot&cursor=" + timeCursor, null).path("code").asText())
                .as("游标排序与请求不一致 → PARAM_ERROR").isEqualTo("400");
    }

    @Test
    void 游标越过末尾返回空页不触发降级() {
        Session author = registerAndLogin("13900002412", "末尾作者");
        seedNormalPosts(author, 5);

        // 手工构造锚在末尾行上的游标（(feed_time, post_id) 最小，续页扫描必为空——
        // 正常端点不会对末页发游标，越尾游标只来自陈旧客户端/行被删后的锚点）
        Map<String, Object> tail = jdbcTemplate.queryForList(
                "SELECT post_id, feed_time FROM post_feed_index WHERE state = 'NORMAL' "
                        + "ORDER BY feed_time ASC, post_id ASC LIMIT 1").get(0);
        // uuid-biz-id 票 03 起游标 4 段：sort | like_count | feed_time | post_id（time 档 like_count 留空）
        String raw = "time||" + ((LocalDateTime) tail.get("feed_time")).toString() + "|" + tail.get("post_id");
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        JsonNode data = fetchFeed(null, cursor);
        assertThat(postIdsOf(data)).as("越过末尾 = 空页（不重不漏的末尾形态）").isEmpty();
        assertThat(data.path("cursor").asText(null)).as("空页不发续页游标").isNull();
        assertThat(data.path("total").asLong()).as("空页仍走索引路径，不降级直查").isEqualTo(-1);
    }

    @Test
    void 索引查询异常时降级DB直查() {
        Session author = registerAndLogin("13900002411", "降级作者");
        List<String> seeded = seedNormalPosts(author, POST_COUNT);
        // 降级直查序 = post.create_time DESC, post_id DESC：UUID 同秒并列由字典序收敛（与发布先后无关），
        // 错开 create_time 让「首页 = 最新发出的 PAGE_SIZE 条」的断言保持成立
        for (int i = 0; i < seeded.size(); i++) {
            jdbcTemplate.update("UPDATE post SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE post_id = ?",
                    seeded.size() - i, seeded.get(i));
        }

        // 黑盒造不出索引表查询故障，白盒构造一个索引扫描必抛异常的域服务：仓储 mock 只覆盖
        // scanFeedIndex，其余方法委托真实实现（真实仓储 + 真实数据，降级后走同一条旧直查）
        IPostRepository failingIndexRepo = mock(IPostRepository.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(postRepository)));
        doThrow(new IllegalStateException("索引表不可用")).when(failingIndexRepo)
                .scanFeedIndex(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());

        FeedPageVO page = new PostServiceImpl(failingIndexRepo, userQueryPort)
                .listPosts("time", null, 1, PAGE_SIZE);
        assertThat(page.isIndexPath()).as("降级后 indexPath=false").isFalse();
        assertThat(page.getNextCursor()).as("降级路径不发游标").isNull();
        assertThat(page.getPosts()).as("降级路径仍出全量首页数据").hasSize(PAGE_SIZE);
        // 旧直查按 create_time DESC, id DESC：首页 = 最新发出的 PAGE_SIZE 条（夹具顺序发布 → 尾段）
        assertThat(page.getPosts()).extracting("postId")
                .containsAll(seeded.subList(POST_COUNT - PAGE_SIZE, POST_COUNT));
    }

    // ---------- 夹具与可观察状态 ----------

    /** 发帖并逐条通过（同秒批量通过 → feed_time 并列，靠 post_id tie-break） */
    private List<String> seedNormalPosts(Session author, int count) {
        List<String> postIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            postIds.add(publishPost(author, "游标夹具帖" + i));
        }
        return postIds;
    }

    private void like(String postId, Session user) {
        assertSuccess(postJson("/api/community/posts/" + postId + "/like", Map.of(), user), "点赞");
    }

    /** 主页 feed 访客请求（data 节点） */
    private JsonNode fetchFeed(String sort, String cursor) {
        String url = "/api/community/posts?pageSize=" + PAGE_SIZE
                + (sort == null ? "&sort=time" : "&sort=" + sort)
                + (cursor == null ? "" : "&cursor=" + cursor);
        JsonNode response = getJson(url, null);
        assertSuccess(response, "feed 列表");
        return response.path("data");
    }

    private List<String> postIdsOf(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.path("list").forEach(node -> ids.add(node.path("postId").asText()));
        return ids;
    }
}
