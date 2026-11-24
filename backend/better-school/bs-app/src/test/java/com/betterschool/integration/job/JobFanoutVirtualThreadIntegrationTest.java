package com.betterschool.integration.job;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.VtJobFanoutProbe;
import com.betterschool.trigger.job.SearchIndexRebuildJob;
import com.betterschool.trigger.job.TradeExpireScanJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度扇出 seam 断言（spec §5.2 #3，票 15）：job 触发测试中断言**扇出项内**为虚拟线程。
 *
 * <p>两个变体各一条：变体 A（单发一批，商品到期下架扫描）与变体 B（游标拉批到空，搜索索引全量重建）。
 * 断言的是线程模型本身——「跑了扇出」看项计数（串行化后恒 0），「跑在什么线程上」看平台线程项计数。
 * xxl-job 的 {@code JobThread} 是 xxl 自有池平台线程，不在断言范围内，测试直接调 handler 方法。
 *
 * <p>票 05 起每日信用任务（{@code DailyCreditJob} 及其 {@code user_daily_active} 事实源）随 sql.md 下线，
 * 变体 B 的「游标拉批到空」改由搜索索引全量重建承担——它同样是「游标推进到空批才是终止条件」的形状。
 * 变体 A 由商品到期下架扫描承担。
 */
@DisplayName("调度扇出 seam：扇出项跑在虚拟线程上")
class JobFanoutVirtualThreadIntegrationTest extends CommunityApiTestBase {

    private static final String TRADE_EXPIRE_PREFIX_PATTERN = "trade-expire-product-\\d+";
    private static final String REBUILD_PREFIX = "search-rebuild-";

    @Autowired
    private TradeExpireScanJob tradeExpireScanJob;

    @Autowired
    private SearchIndexRebuildJob searchIndexRebuildJob;

    @Test
    @DisplayName("变体 A 单发一批：商品到期下架扫描的扇出项为虚拟线程")
    void variantAFanoutItemsRunOnVirtualThreads() {
        // 直插一行已过 30 天到期窗口的在售商品：扫描集为空时扇出根本没东西可跑，断言会假红
        insertExpiredProduct();

        VtJobFanoutProbe.reset();
        tradeExpireScanJob.scan(null);

        assertThat(VtJobFanoutProbe.itemCount())
                .as("扇出项应被调用——计数为 0 即该 job 被改回串行（不经扇出模板）").isPositive();
        assertThat(VtJobFanoutProbe.platformItemCount())
                .as("扇出项必须全部跑在虚拟线程上").isZero();
        assertThat(VtJobFanoutProbe.threadNames())
                .as("扇出线程按模板命名：前缀 + 自增序号")
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).matches(TRADE_EXPIRE_PREFIX_PATTERN));
    }

    @Test
    @DisplayName("变体 B 游标拉批到空：搜索索引全量重建的扇出项为虚拟线程")
    void variantBFanoutItemsRunOnVirtualThreads() {
        Session author = registerAndLogin("13900001102", "重建作者");
        // 三类索引各摆一行可进索引的数据：重建的每一类都要有扇出项，断言才盯得住线程模型
        publishPost(author, "扇出重建帖");
        insertOnSaleProduct(author.userId());

        VtJobFanoutProbe.reset();
        searchIndexRebuildJob.rebuild(null);

        assertThat(VtJobFanoutProbe.itemCount())
                .as("扇出项应被调用——计数为 0 即该 job 被改回串行（不经扇出模板）").isPositive();
        assertThat(VtJobFanoutProbe.platformItemCount())
                .as("扇出项必须全部跑在虚拟线程上").isZero();
        assertThat(VtJobFanoutProbe.threadNames())
                .as("扇出线程按模板命名：前缀 + 自增序号")
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).matches(REBUILD_PREFIX + "[a-z]+-\\d+"));
    }

    /** 直插一行已到期的在售商品（expire_at 回拨 31 天，进到期扫描集） */
    private void insertExpiredProduct() {
        String userId = registerAndLogin("13900001101", "扇出卖家").userId();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, category, state, "
                        + "create_time, update_time, expire_at) "
                        + "VALUES (?, ?, '扇出到期商品', 'BOOKS', 'ON_SALE', NOW(), NOW(), DATE_SUB(NOW(), INTERVAL 31 DAY))",
                UUID.randomUUID().toString(), userId);
    }

    /** 直插一行在售商品（业务 id 是 UUID 字符串，uuid-biz-id 票 05） */
    private void insertOnSaleProduct(String userId) {
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, category, state, "
                + "create_time, update_time) VALUES (?, ?, '扇出商品', 'BOOKS', 'ON_SALE', NOW(), NOW())",
                UUID.randomUUID().toString(), userId);
    }
}
