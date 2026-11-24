package com.betterschool.integration.community;

import com.betterschool.infrastructure.adapter.repository.CountAction;
import com.betterschool.infrastructure.adapter.repository.CountEvent;
import com.betterschool.infrastructure.adapter.repository.community.CommunityRepository;
import com.betterschool.infrastructure.adapter.repository.LikeState;
import com.betterschool.integration.support.CommunityApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 落库方法的**批内撞键定位**（票 03 验收：只把 {@code DUPLICATE} 回给命中的那几个）。
 *
 * <p>为什么不在 HTTP 层测：一批 = 落到同一个热点组（同一帖子）上的若干笔赞。用 HTTP 造出「同批」需要
 * 精确的并发时序，而这里要验的是落库方法自己的裁决——它在合并器眼里就是一个「给一组事件、回一组结果」
 * 的函数，直接调它就是最短路径，也最稳定。
 *
 * <p>{@code INSERT IGNORE} 只回影响行数（比如 3 件只插进 2 行），本类锁的就是「哪几件没插进去」：
 * 回查严格限定在批内，命中的拿 {@code DUPLICATE}，其余照常 {@code LIKED}；计数只加真正插入的行数。
 */
@DisplayName("计数落库方法：批内部分撞键定位到具体请求")
class LikeCountFlushHandlerIntegrationTest extends CommunityApiTestBase {

    /** 不存在的帖子 id（UUID 化后为字符串形态）：本类只测关系行 + 窄表的裁决，帖子外键与可见性由域服务前置，不在这层 */
    private static final String POST_ID = "9900001";

    private static final String COMMENT_ID = "9900002";

    @Autowired
    private CommunityRepository flushHandler;

    @Test
    @DisplayName("点赞：同批三件里两件撞键，只把 DUPLICATE 回给命中的，计数按影响行数加")
    void 批内撞键只把Duplicate回给命中的那几件() {
        // 先让用户 101 已经赞过（第一批只含他一件）
        List<LikeState> first = flushHandler.flushGroup(CountAction.POST_LIKE, POST_ID,
                List.of(like("u101")));
        assertThat(first).containsExactly(LikeState.LIKED);
        assertThat(postLikeCountOf()).isEqualTo(1);

        // 第二批：101（已赞）、102（新）、101（同批重复）——三件里只有一件该真正插入
        List<LikeState> second = flushHandler.flushGroup(CountAction.POST_LIKE, POST_ID,
                List.of(like("u101"), like("u102"), like("u101")));

        assertThat(second)
                .as("顺序与入参一致：已赞的与同批重复的都回 DUPLICATE，只有 102 是真写的")
                .containsExactly(LikeState.DUPLICATE, LikeState.LIKED, LikeState.DUPLICATE);
        assertThat(likeRowCount()).as("关系行 = 101 + 102 两行").isEqualTo(2);
        assertThat(postLikeCountOf()).as("计数按影响行数加，与关系行条数一致").isEqualTo(2);
    }

    @Test
    @DisplayName("取消赞：删到的回 UNLIKED、没删到的回 NOT_FOUND，计数按删掉的行数回落")
    void 取消赞按删掉的行数回落并区分未赞过() {
        flushHandler.flushGroup(CountAction.POST_LIKE, POST_ID, List.of(like("u201"), like("u202")));
        assertThat(postLikeCountOf()).isEqualTo(2);

        // 201 真有赞、299 其实没赞：批量 DELETE 只删掉一行
        List<LikeState> results = flushHandler.flushGroup(CountAction.POST_UNLIKE, POST_ID,
                List.of(like("u201"), like("u299")));

        assertThat(results).containsExactly(LikeState.UNLIKED, LikeState.NOT_FOUND);
        assertThat(likeRowCount()).as("只删掉 201").isEqualTo(1);
        assertThat(postLikeCountOf()).as("计数只减真正删掉的那 1 行").isEqualTo(1);
    }

    @Test
    @DisplayName("评论点赞：计数在计数窄表上，唯一键幂等，重复赞不重复计")
    void 评论点赞计数在窄表上且幂等() {
        jdbcTemplate.update("""
                INSERT INTO post_comment (comment_id, post_id, user_id, content, state, root_id, parent_id,
                                          reply_count, create_time, update_time)
                VALUES (?, '9900001', 1, '落库方法用例的评论', 'NORMAL', '0', '0', 0, NOW(), NOW())
                """, COMMENT_ID);
        // 计数行诞生于评论发布：直造 NORMAL 评论行时必须同造窄表行，
        // 否则 UPDATE 命中 0 行、计数永远不动（那不是本用例要验的东西）
        jdbcTemplate.update("""
                INSERT INTO post_comment_like_count (comment_id, like_count, create_time, update_time)
                VALUES (?, 0, NOW(), NOW())
                """, COMMENT_ID);

        List<LikeState> first = flushHandler.flushGroup(CountAction.COMMENT_LIKE, COMMENT_ID,
                List.of(commentLike("u301"), commentLike("u302")));
        assertThat(first).containsExactly(LikeState.LIKED, LikeState.LIKED);
        assertThat(commentLikeCount()).as("两人赞 → 窄表 like_count = 2").isEqualTo(2);

        List<LikeState> second = flushHandler.flushGroup(CountAction.COMMENT_LIKE, COMMENT_ID,
                List.of(commentLike("u301")));
        assertThat(second).containsExactly(LikeState.DUPLICATE);
        assertThat(commentLikeCount()).as("重复赞不重复计").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_comment_like WHERE comment_id = ?", Integer.class, COMMENT_ID))
                .as("关系行只有两行").isEqualTo(2);
    }

    // ---------- 工具 ----------

    private static CountEvent like(String userId) {
        return new CountEvent(CountAction.POST_LIKE, POST_ID, userId);
    }

    private static CountEvent commentLike(String userId) {
        return new CountEvent(CountAction.COMMENT_LIKE, COMMENT_ID, userId);
    }

    private long likeRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_like WHERE post_id = ?", Integer.class, POST_ID);
        return count == null ? 0 : count;
    }

    private long postLikeCountOf() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT like_count FROM post_like_count WHERE post_id = ?", Integer.class, POST_ID);
        return count == null ? 0 : count;
    }

    /** 评论点赞计数真源：窄表 post_comment_like_count（票 02 起；主列 post_comment.like_count 已随票 04 删除） */
    private long commentLikeCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT like_count FROM post_comment_like_count WHERE comment_id = ?", Integer.class, COMMENT_ID);
        return count == null ? 0 : count;
    }
}
