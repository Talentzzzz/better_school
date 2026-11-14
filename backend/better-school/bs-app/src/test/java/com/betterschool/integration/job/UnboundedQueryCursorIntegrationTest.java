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
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无界查询游标化（spec §4.3 C，票 15）：全量重建与扫批的结果集是无界的，必须能靠游标一页页推进到空批，
 * 且**处理过的行不会重复出现**——这正是它们此前「只取前 N 行」的病根。
 *
 * <p>游标与返回的业务 id 同型（uuid-biz-id 票 05：业务 id 统一是 UUID 字符串，游标按业务 id
 * 字典序推进、起始空串），夹具直接以随机 UUID 作为业务列写入。
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
        publishPost(author, "游标帖一");
        publishPost(author, "游标帖二");
        publishPost(author, "游标帖三");

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

        assertPagedToEmpty(cursor -> tradeRepository.listIdsAfter(cursor, PAGE), 3, "");
        assertPagedToEmpty(cursor -> errandRepository.listIdsAfter(cursor, PAGE), 3, "");
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
     * 直插一行在售商品，返回业务 id。业务 id 是 UUID 字符串（uuid-biz-id 票 05）：
     * 发号列随行一次写入，游标按 product_id 字典序推进（本用例用随机 UUID 保证字典序不重复）。
     */
    private String insertProductFixture(String userId) {
        String productId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, category, state, "
                + "create_time, update_time) VALUES (?, ?, 'BOOKS', 'ON_SALE', NOW(), NOW())", productId, userId);
        return productId;
    }

    /** 直插一行已发布跑腿单，返回业务 id（口径同上） */
    private String insertErrandFixture(String userId) {
        String errandId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, state, create_time, update_time) "
                        + "VALUES (?, ?, 'BUY', '游标单', '宿舍楼', '食堂', 5.00, 'PUBLISHED', NOW(), NOW())", errandId, userId);
        return errandId;
    }
}
