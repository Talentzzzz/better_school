package com.betterschool.integration.community;

import com.betterschool.infrastructure.merge.count.CountAction;
import com.betterschool.infrastructure.merge.count.CountEvent;
import com.betterschool.infrastructure.merge.count.CountMerger;
import com.betterschool.infrastructure.merge.count.ICountFlushHandler;
import com.betterschool.infrastructure.merge.count.LikeState;
import com.betterschool.infrastructure.merge.count.ViewMergeWriter;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子计数的**合并写**语义（票 03 / 04 / 05 验收 C1、C4、C5；hot-row-merge 之后）。
 *
 * <p>原名 {@code PostCountAggregationIntegrationTest}：聚合器退出这条链路后，用例测的是「合并写」
 * 而不是「聚合」，断言里也不再有任何「等轮询」。
 *
 * <ul>
 *   <li><b>点赞 / 取消赞 / 评论赞</b>：提交给合并器 → 等窗口 → 一组一事务落库 → 返回。接口返回成功时
 *       计数<b>已经精确</b>，所以断言一律是「立刻相等」（C1）。</li>
 *   <li><b>浏览</b>：fire-and-forget，入队即返回，窗口过后一组一个事务三步落库——
 *       {@code INSERT IGNORE INTO user_view_history}（affected = 新增 UV 数）→ 刷新本组 {@code viewed_at}
 *       → {@code post_view_count += 新增数}（feed-index-uv 票 02）。UV 口径：INSERT 成功才算浏览，
 *       重复进详情只刷 viewed_at 不加数，匿名访问不计数。仍然<b>允许丢</b>，丢弃数可读；
 *       排空后计数精确（重复浏览幂等，不存在「少计一次增量」的永久偏差）。</li>
 *   <li><b>详情读</b>：缓存只存内容、三个计数每次回库拼装（票 05），缓存命中路径的三个数恒等于
 *       库值（C5，{@code detailCountsNeverComeFromCache}）。</li>
 * </ul>
 */
@DisplayName("帖子计数：点赞/取消赞同步合并写（返回即精确），浏览 fire-and-forget（允许丢）")
class PostCountMergeIntegrationTest extends CommunityApiTestBase {

    /** 窗口之后才会有写：等排空用它，等不到即断言失败 */
    private static final long DRAIN_MILLIS = 5_000;

    @Autowired
    private CountMerger countMerger;

    @Autowired
    private ViewMergeWriter viewMergeWriter;

    @Autowired
    private ICountFlushHandler flushHandler;

    @Test
    @DisplayName("点赞返回成功时 关系行数 == 窄表计数，无需等待；重复赞报错且不重复计")
    void 点赞成功即精确且重复赞不重复计() {
        Session author = registerAndLogin("13800007001", "计数作者1");
        Session liker = registerAndLogin("13800007002", "点赞者1");
        Session otherLiker = registerAndLogin("13800007003", "点赞者2");
        String postId = publishPost(author, "同步合并写的帖子正文");

        assertSuccess(postJson("/api/community/posts/" + postId + "/like", null, liker), "点赞");

        // ★ C1 的核心断言：接口刚返回、什么都没等，关系行与窄表计数就已经相等
        assertThat(likeRowCountOf(postId)).as("关系行").isEqualTo(1);
        assertThat(postLikeCountOf(postId)).as("窄表计数（返回即精确，不等窗口）").isEqualTo(1);

        assertSuccess(postJson("/api/community/posts/" + postId + "/like", null, otherLiker), "第二人点赞");
        assertThat(likeRowCountOf(postId)).as("两人赞").isEqualTo(2);
        assertThat(postLikeCountOf(postId)).as("计数跟到 2").isEqualTo(2);

        // C6：重复赞由唯一键裁决——对外是业务错误，计数不重复推进
        JsonNode repeat = postJson("/api/community/posts/" + postId + "/like", null, liker);
        assertThat(repeat.path("code").asText()).as("重复点赞应报业务错误").isNotEqualTo("200");
        assertThat(likeRowCountOf(postId)).as("重复赞不新增关系行").isEqualTo(2);
        assertThat(postLikeCountOf(postId)).as("重复赞不重复计").isEqualTo(2);

        // C5：「先赞 → 立刻读详情就是新值」由 detailCountsNeverComeFromCache 专测（详情缓存不存计数）
    }

    @Test
    @DisplayName("C5 详情缓存不存计数：先赞 → 再读详情（缓存命中路径），三个数恒等于库值")
    void detailCountsNeverComeFromCache() {
        Session author = registerAndLogin("13800007012", "计数作者5");
        Session liker = registerAndLogin("13800007013", "点赞者6");
        Session commenter = registerAndLogin("13800007014", "评论人1");
        String postId = publishPost(author, "详情缓存不存计数的帖子");

        // 第一次读：回源并回填详情缓存（此后点赞 / 评论计数动作都不再失效它，读的必然是缓存命中路径）
        assertSuccess(getJson("/api/community/posts/" + postId, null), "第一次读详情（回填缓存）");
        assertThat(detailCacheBucket(postId).isExists()).as("预热后详情缓存应存在").isTrue();

        // 先赞（同步合并写，返回即精确）+ 评论（发布即计数）：两个计数动作都不碰缓存
        assertSuccess(postJson("/api/community/posts/" + postId + "/like", null, liker), "点赞");
        publishCommentAndGetId(postId, commenter, "缓存不存计数");

        // 排空在途浏览件，让 view_count 的库值在「取库值 → 读详情」之间保持静止（浏览允许丢，不能等它）
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();
        long likeInDb = postLikeCountOf(postId);
        long commentInDb = postCommentCountOf(postId);
        int viewInDb = postViewCountOf(postId);

        // ★ C5 的核心断言：缓存命中路径读详情，三个数恒等于库值——旧实现这里会读到缓存里的计数快照 0
        JsonNode detail = getJson("/api/community/posts/" + postId, author);
        assertSuccess(detail, "第二次读详情（缓存命中）");
        assertThat(detail.path("data").path("likeCount").asLong())
                .as("likeCount 恒等于库值").isEqualTo(likeInDb).isEqualTo(1);
        assertThat(detail.path("data").path("commentCount").asLong())
                .as("commentCount 恒等于库值").isEqualTo(commentInDb).isEqualTo(1);
        assertThat(detail.path("data").path("viewCount").asLong())
                .as("viewCount 恒等于库值（仅作者可见）").isEqualTo(viewInDb);

        // 缓存里摸不到任何计数：进缓存前置空，命中路径的三个数恒由读路径回库拼装
        JsonNode cached = readTree(detailCacheBucket(postId).get());
        assertThat(cached.path("likeCount").asLong()).as("缓存里的 likeCount 恒为空置 0").isZero();
        assertThat(cached.path("commentCount").asLong()).as("缓存里的 commentCount 恒为空置 0").isZero();
        assertThat(cached.path("viewCount").asLong()).as("缓存里的 viewCount 恒为空置 0").isZero();
    }

    @Test
    @DisplayName("取消赞：计数回落到关系行数、不为负；未赞过取消报错且计数不变")
    void 取消赞后计数回落到关系行数且不为负() {
        Session author = registerAndLogin("13800007004", "计数作者2");
        Session liker1 = registerAndLogin("13800007005", "点赞者3");
        Session liker2 = registerAndLogin("13800007006", "点赞者4");
        Session liker3 = registerAndLogin("13800007007", "点赞者5");
        Session stranger = registerAndLogin("13800007008", "没赞过的人");
        String postId = publishPost(author, "取消赞回落口径的帖子");

        for (Session liker : List.of(liker1, liker2, liker3)) {
            assertSuccess(postJson("/api/community/posts/" + postId + "/like", null, liker), "点赞");
        }
        assertThat(likeRowCountOf(postId)).as("三人赞").isEqualTo(3);
        assertThat(postLikeCountOf(postId)).as("计数 3").isEqualTo(3);

        // 取消一个：关系行与计数同时回落，返回即精确
        assertSuccess(deleteJson("/api/community/posts/" + postId + "/like", liker1), "取消点赞");
        assertThat(likeRowCountOf(postId)).isEqualTo(2);
        assertThat(postLikeCountOf(postId)).as("计数回落到关系行条数").isEqualTo(2);

        // 未赞过的人取消：业务错误、一行不动
        JsonNode notLiked = deleteJson("/api/community/posts/" + postId + "/like", stranger);
        assertThat(notLiked.path("code").asText()).as("未赞过时取消应报业务错误").isNotEqualTo("200");
        assertThat(postLikeCountOf(postId)).as("没赞过的人取消不改计数").isEqualTo(2);

        // GREATEST(0, …) 兜底：窄表被人为压低到 0 后再取消，计数不能变负
        jdbcTemplate.update("UPDATE post_like_count SET like_count = 0 WHERE post_id = ?", postId);
        assertSuccess(deleteJson("/api/community/posts/" + postId + "/like", liker2), "取消点赞（计数已压低）");
        assertThat(likeRowCountOf(postId)).as("关系行已删到 1").isEqualTo(1);
        assertThat(postLikeCountOf(postId)).as("计数不为负（clamp 0）").isZero();

        // 最后一人取消：关系行清零，计数仍是 0（不会 -1）
        assertSuccess(deleteJson("/api/community/posts/" + postId + "/like", liker3), "取消最后一个赞");
        assertThat(likeRowCountOf(postId)).as("关系行清零").isZero();
        assertThat(postLikeCountOf(postId)).as("计数不为负").isZero();
    }

    @Test
    @DisplayName("C4 浏览 UV：首次 +1、重复不计数但 viewed_at 刷新、匿名不计入；旧列停写冻结")
    void viewCountIsUvDedupedAndAnonymousNotCounted() {
        Session author = registerAndLogin("13800007009", "计数作者3");
        Session reader = registerAndLogin("13800007010", "浏览者1");
        Session otherReader = registerAndLogin("13800007021", "浏览者2");
        String postId = publishPost(author, "浏览走 UV 口径的帖子");
        long droppedBefore = viewMergeWriter.droppedViews();

        // 首次浏览：排空后明细 1 行、加速列 1
        assertSuccess(getJson("/api/community/posts/" + postId, reader), "首次浏览详情");
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();
        assertThat(viewHistoryRowCount(postId, reader.userId())).as("明细行已落").isEqualTo(1);
        assertThat(postViewCountOf(postId)).as("首次浏览 UV = 1").isEqualTo(1);

        // 重复浏览：不计数，但 viewed_at 刷新——把明细行拨回远古时刻，再浏览一次，必须是新时刻
        jdbcTemplate.update("UPDATE user_view_history SET viewed_at = '2000-01-01' WHERE post_id = ? AND user_id = ?",
                postId, reader.userId());
        assertSuccess(getJson("/api/community/posts/" + postId, reader), "重复浏览详情");
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();
        assertThat(postViewCountOf(postId)).as("重复浏览不计数").isEqualTo(1);
        assertThat(viewedAtOf(postId, reader.userId()))
                .as("重复浏览刷新 viewed_at（远古值被拨回当下）")
                .isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));

        // 第二个浏览者：+1；浏览没有取消语义，计数只增不减
        assertSuccess(getJson("/api/community/posts/" + postId, otherReader), "第二人浏览详情");
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();
        assertThat(postViewCountOf(postId)).as("两个 UV").isEqualTo(2);
        assertThat(viewHistoryRowCount(postId, otherReader.userId())).isEqualTo(1);

        // 匿名访问：落不了明细行（user_view_history 主键的一半是 user_id），按「INSERT 成功才算浏览」不计入
        assertSuccess(getJson("/api/community/posts/" + postId, null), "匿名浏览详情");
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();
        assertThat(postViewCountOf(postId)).as("匿名浏览不计数").isEqualTo(2);

        // 丢弃数必须可读，且健康路径不该有丢弃（缓冲满 / 停机中才走降级）
        assertThat(viewMergeWriter.droppedViews()).as("浏览丢弃数可读、健康路径为 0")
                .isEqualTo(droppedBefore);

        // MQ 骨架已清零：浏览不再落任何计数消息（两个 topic 常量也已删除，这里用字面量兜住回归）
        assertThat(outboxCountOf("bs-post-count")).as("浏览不再走 outbox + MQ").isZero();
    }

    @Test
    @DisplayName("同一窗口的 N 件浏览合并成一组一个事务：明细按主键去重，加速列 += 新增数")
    void 一组浏览件合并成一组一个事务按主键去重() {
        Session author = registerAndLogin("13800007011", "计数作者4");
        String postId = publishPost(author, "一组浏览件合并成一组一个事务");
        // user_id 是 UUID 字符串后无相邻可推，四个不同浏览者用四个固定串
        String u1 = "viewer-1";
        String u2 = "viewer-2";
        String u3 = "viewer-3";
        String u4 = "viewer-4";

        List<LikeState> first = flushHandler.flushGroup(CountAction.POST_VIEW, postId,
                List.of(viewEvent(postId, u1), viewEvent(postId, u2), viewEvent(postId, u3)));

        assertThat(first).as("浏览件没有业务结果，但必须等长回填（否则引擎会记 failed、合并比失真）")
                .hasSize(3).doesNotContainNull();
        assertThat(postViewCountOf(postId)).as("三个新用户 → 明细 3 行、加速列 3").isEqualTo(3);

        // 第二组：u1 已看过（撞主键）、u4 与 u4 同批重复（去重后一件）→ 只新增 1 个 UV
        jdbcTemplate.update("UPDATE user_view_history SET viewed_at = '2000-01-01' WHERE post_id = ? AND user_id = ?",
                postId, u1);
        List<LikeState> second = flushHandler.flushGroup(CountAction.POST_VIEW, postId,
                List.of(viewEvent(postId, u1), viewEvent(postId, u4), viewEvent(postId, u4)));

        assertThat(second).hasSize(3).doesNotContainNull();
        assertThat(postViewCountOf(postId)).as("只有 u4 是新 UV，加速列按 INSERT 影响行数加").isEqualTo(4);
        assertThat(viewedAtOf(postId, u1)).as("已看过的 u1 不加数，但 viewed_at 被刷新")
                .isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(viewHistoryRowCount(postId, u2)).as("旁观用户 u2 的明细行仍是 1").isEqualTo(1);
        assertThat(postLikeCountOf(postId)).isZero();
    }

    // ---------- 工具 ----------

    private static CountEvent viewEvent(String postId, String userId) {
        return new CountEvent(CountAction.POST_VIEW, postId, userId);
    }

    private long likeRowCountOf(String postId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_like WHERE post_id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    /** 浏览 UV 加速列（真相源 user_view_history 的行数）；行懒建，0 浏览的帖子无行 */
    private int postViewCountOf(String postId) {
        List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT view_count FROM post_view_count WHERE post_id = ?", Integer.class, postId);
        return counts.isEmpty() ? 0 : counts.get(0);
    }


    private int viewHistoryRowCount(String postId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_view_history WHERE post_id = ? AND user_id = ?",
                Integer.class, postId, userId);
        return count == null ? 0 : count;
    }

    private LocalDateTime viewedAtOf(String postId, String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT viewed_at FROM user_view_history WHERE post_id = ? AND user_id = ?",
                LocalDateTime.class, postId, userId);
    }

    private int outboxCountOf(String destination) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ?", Integer.class, destination);
        return count == null ? 0 : count;
    }
}
