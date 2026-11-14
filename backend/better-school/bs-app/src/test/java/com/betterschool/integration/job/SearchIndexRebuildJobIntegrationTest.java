package com.betterschool.integration.job;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.FakeSearchRepository;
import com.betterschool.trigger.job.SearchIndexRebuildJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索索引全量重建（spec §4.3 C，票 15）：四类索引都从「取全部 id」改成「游标拉批到空批 + 批内扇出」，
 * 本测试盯住三件事——重建能跑到头（游标真的在推进）、只有公开状态的实体进索引（过滤没丢）、
 * 每类的全部合格行都回填了（扇出结果在主线程汇总没漏）。
 *
 * <p>ES 侧用 {@link FakeSearchRepository} 替身（测试栈刻意不连 ES），断言的是重建算法而非 ES 语义。
 */
@DisplayName("搜索索引全量重建：游标拉批到空批 + 批内扇出 + 状态过滤")
class SearchIndexRebuildJobIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private SearchIndexRebuildJob searchIndexRebuildJob;

    @Test
    void 重建回填四类索引且只收公开状态的行() {
        Session author = registerAndLogin("13900001301", "重建作者");
        Session other = registerAndLogin("13900001302", "重建路人");
        // 每类都摆「该进索引」与「不该进索引」各一行，过滤是否正确才有断言力
        String visiblePostId = publishPost(author, "重建可见帖");
        saveDraftPost(author, "重建草稿帖");
        String onSaleProductId = insertProduct(author.userId(), "ON_SALE");
        insertProduct(author.userId(), "SOLD");
        String publishedErrandId = insertErrand(other.userId(), "PUBLISHED");
        insertErrand(other.userId(), "CANCELLED");

        FakeSearchRepository.reset();
        searchIndexRebuildJob.rebuild(null);

        assertThat(FakeSearchRepository.clearedIndexes())
                .as("四类索引各清一次，顺序固定").containsExactly("post", "product", "errand", "user");
        assertThat(FakeSearchRepository.postIds())
                .as("只有 NORMAL 帖子进索引，草稿帖不进").containsExactly(visiblePostId);
        assertThat(FakeSearchRepository.productIds())
                .as("只有 ON_SALE 商品进索引，已售不进").containsExactly(onSaleProductId);
        assertThat(FakeSearchRepository.errandIds())
                .as("只有公开态跑腿单进索引，已取消不进").containsExactly(publishedErrandId);
        assertThat(FakeSearchRepository.userIds())
                .as("全部 NORMAL 用户进索引").containsExactlyInAnyOrder(author.userId(), other.userId());
    }

    /**
     * 直插一行商品，返回业务 id。业务 id 是 UUID 字符串（uuid-biz-id 票 05）：
     * 发号列随行一次写入，游标按 product_id 字典序推进。
     */
    private String insertProduct(String userId, String state) {
        // category 必须给：索引文档要 category.name()（线上由发布用例保证非空，这里摆真数据）
        String productId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, category, state, "
                        + "create_time, update_time) VALUES (?, ?, '重建商品', 'BOOKS', ?, NOW(), NOW())",
                productId, userId, state);
        return productId;
    }

    /** 直插一行跑腿单，返回业务 id（口径同上） */
    private String insertErrand(String userId, String state) {
        String errandId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, state, create_time, update_time) "
                        + "VALUES (?, ?, 'BUY', '重建单', '宿舍楼', '食堂', 5.00, ?, NOW(), NOW())",
                errandId, userId, state);
        return errandId;
    }

    /** 保存草稿帖（DRAFT 不进索引），返回 postId */
    private String saveDraftPost(Session author, String title) {
        JsonNode response = postJson("/api/community/posts/draft",
                Map.of("title", title, "content", "正文内容，重建用例"), author);
        assertSuccess(response, "保存草稿帖");
        return response.path("data").asText();
    }
}
