package com.betterschool.integration.job;

import com.betterschool.domain.community.adapter.repository.ICommunityRepository;
import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无界查询游标化（spec §4.3 C，票 15）：全量重建与扫批的结果集是无界的，必须能靠游标一页页推进到空批，
 * 且**处理过的行不会重复出现**——这正是它们此前「只取前 N 行」的病根。
 *
 * <p>游标列是各表的物理自增 id（分配序），返回的是业务 id；两者同值（应用同事务写回，
 * 见 sql.md 主键口径），夹具也照此口径把业务列绑到 id 上，否则「按 id 推进」与「取到的业务 id」会脱节。
 * {@code PostDetailCache.initBloom()} 的全量 id 是刻意的例外（一次性预热、不是循环），不在此断言。
 */
@DisplayName("无界查询游标页：按 id 升序推进、分页不重复、推进到空批终止")
class UnboundedQueryCursorIntegrationTest extends CommunityApiTestBase {

    private static final int PAGE = 2;

    @Autowired
    private ICommunityRepository communityRepository;

    @Autowired
    private ITradeRepository tradeRepository;

    @Autowired
    private IErrandRepository errandRepository;

    @Autowired
    private IUserRepository userRepository;

    @Test
    @DisplayName("帖子：搜索索引全量重建的游标页")
    void postIdsPageByCursor() {
        Session author = registerAndLogin("13900001201", "游标作者");
        publishPendingPost(author, "游标帖一");
        publishPendingPost(author, "游标帖二");
        publishPendingPost(author, "游标帖三");

        assertPagedToEmpty(cursor -> communityRepository.listIdsAfter(cursor, PAGE), 3, "");
    }

    @Test
    @DisplayName("用户：搜索索引全量重建的游标页")
    void userIdsPageByCursor() {
        registerAndLogin("13900001202", "游标用户一");
        registerAndLogin("13900001203", "游标用户二");
        registerAndLogin("13900001204", "游标用户三");

        // user 游标是 UUID 字符串，起始游标空串
        assertPagedToEmpty(cursor -> userRepository.listUserIdsAfter(cursor, PAGE), 3, "");
    }

    @Test
    @DisplayName("商品与跑腿：搜索索引全量重建的游标页")
    void productAndErrandIdsPageByCursor() {
        String userId = registerAndLogin("13900001205", "游标卖家").userId();
        for (int i = 0; i < 3; i++) {
            insertProductFixture(userId);
            insertErrandFixture(userId);
        }

        assertPagedToEmpty(cursor -> tradeRepository.listIdsAfter(cursor, PAGE), 3, 0L);
        assertPagedToEmpty(cursor -> errandRepository.listIdsAfter(cursor, PAGE), 3, 0L);
    }

    /**
     * 游标页的通用断言：逐页推进到空批（唯一终止条件）。页内按 id 升序、本页首行严格大于上一页末行
     * ⇒ 处理过的行不会重复出现；游标若没推进就会在同一页上打转，用总行数上限兜住死循环。
     */
    private <I extends Comparable<I>> void assertPagedToEmpty(Function<I, List<I>> pageOfIds, int expectedRows, I startCursor) {
        List<I> collected = new ArrayList<>();
        I cursor = startCursor;
        while (true) {
            List<I> page = pageOfIds.apply(cursor);
            if (page.isEmpty()) {
                break;
            }
            assertThat(page).as("每页按 id 升序").isSorted();
            assertThat(page.get(0).compareTo(cursor)).as("游标推进：本页首行严格大于上一页末行（处理过的行不得重复出现）")
                    .isPositive();
            assertThat(collected).as("分页不得重复").doesNotContainAnyElementsOf(page);
            collected.addAll(page);
            assertThat(collected.size()).as("游标不推进会死循环，取到的行数不可能超过数据量")
                    .isLessThanOrEqualTo(expectedRows);
            cursor = page.get(page.size() - 1);
        }
        assertThat(collected).as("推进到空批之前应取满全部 %s 行", expectedRows).hasSize(expectedRows);
    }

    /**
     * 直插一行在售商品，返回业务 id。业务列 NOT NULL 无默认值：先插 0 再由本方法绑到物理 id
     * （应用侧 {@code bindBizId} 的同口径复刻），保证「业务 id == 游标 id」这条前提在本用例成立。
     */
    private long insertProductFixture(String userId) {
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, category, state, "
                + "create_time, update_time) VALUES (0, ?, 'BOOKS', 'ON_SALE', NOW(), NOW())", userId);
        jdbcTemplate.update("UPDATE product SET product_id = id WHERE product_id = 0");
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM product", Long.class);
    }

    /** 直插一行已发布跑腿单，返回业务 id（口径同上） */
    private long insertErrandFixture(String userId) {
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                + "pickup_location, delivery_location, reward, time_limit_hours, state, create_time, update_time) "
                + "VALUES (0, ?, 'BUY', '游标单', '宿舍楼', '食堂', 5.00, 2, 'PUBLISHED', NOW(), NOW())", userId);
        jdbcTemplate.update("UPDATE errand_order SET errand_order_id = id WHERE errand_order_id = 0");
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM errand_order", Long.class);
    }

    /** 直接发帖（先审后发：落库即待审），返回 postId */
    private String publishPendingPost(Session author, String title) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", title, "content", "正文内容，游标用例"), author);
        assertSuccess(response, "发帖");
        return response.path("data").asText();
    }
}
