package com.betterschool.integration.trade;

import com.betterschool.integration.support.CommunityApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * market-feed-uv 票 01：市集（闲置 + 跑腿）两张 feed 索引表的存量回填脚本
 * （backend/better-school/sql/backfill/market-feed-uv-backfill.sql）。
 *
 * <p>回填口径钉死——行集 = 商品 ON_SALE / 跑腿 PUBLISHED（大厅公开口径，前端大厅恒传 status=PUBLISHED）、
 * feed_time 取审核记录里最近一次通过时刻（查不到审批流水取 create_time 兜底）、价格从主表抄入；
 * 索引热度键**不抄存量 PV**（票 06 删列后 product.view_count 已不存在，索引行按默认 0 起，切码后由
 * 浏览旁路累计——存量 PV 本就要在切码前清零，抄不抄都落 0）。脚本直接对测试库执行文件本身
 * （不内联复制语句，防漂移）；开发库/测试库平时不跑回填（由 schema.sql 重建），
 * 本测试是把线上库的一次性操作当被测对象钉住。
 */
@DisplayName("市集 feed 索引存量回填：大厅口径行集 / feed_time 最近通过 / 价格抄入 / 幂等追平")
class MarketFeedUvBackfillIntegrationTest extends CommunityApiTestBase {

    private static final String BACKFILL_RELATIVE = Paths.get("sql", "backfill", "market-feed-uv-backfill.sql").toString();

    @Test
    void 商品回填只收ON_SALE且feed_time取最近一次通过时刻() {
        Session author = registerAndLogin("13900001501", "商品回填作者");

        // 无审批流水的存量老商品（直插 ON_SALE，绕过审核链路）——feed_time 兜底 create_time
        String legacyProductId = insertProduct(author.userId(), "ON_SALE", "5.00");
        // 有审批流水但无按人明细的在售商品——索引热度键不抄存量 PV，按默认 0 起
        String viewedProductId = insertProduct(author.userId(), "ON_SALE", "12.50");
        // 多轮审核：第一轮三天前通过、第二轮一小时前重提再通过——feed_time 应取最近一次
        String reapprovedProductId = insertProduct(author.userId(), "ON_SALE", "8.00");
        insertApproveReview("PRODUCT", reapprovedProductId, author.userId(), 1, ts(-3 * 24));
        insertApproveReview("PRODUCT", reapprovedProductId, author.userId(), 2, ts(-1));
        // 非 ON_SALE 商品不进索引（售出/被拒）
        String soldProductId = insertProduct(author.userId(), "SOLD", "9.99");
        String rejectedProductId = insertProduct(author.userId(), "REJECTED", "1.00");

        runScript(BACKFILL_RELATIVE);

        List<String> indexedIds = jdbcTemplate.queryForList("SELECT product_id FROM product_feed_index", String.class);
        assertThat(indexedIds).as("只回填 ON_SALE 商品：售出/被拒不进索引")
                .containsExactlyInAnyOrder(legacyProductId, viewedProductId, reapprovedProductId);

        assertThat(queryIndexFeedTime("product_feed_index", "product_id", viewedProductId))
                .as("查不到审批流水兜底 create_time")
                .isEqualTo(queryColumnTime("product", "product_id", "create_time", viewedProductId));
        assertThat(queryIndexFeedTime("product_feed_index", "product_id", reapprovedProductId))
                .as("feed_time 取最近一次通过（重提再通过 > 首次通过）")
                .isEqualTo(lastApproveTime("PRODUCT", reapprovedProductId));

        assertThat(queryIndexPrice("product_feed_index", "product_id", viewedProductId))
                .as("price 自主表抄入").isEqualTo("12.50");
        assertThat(indexViewCountOf("product_feed_index", "product_id", viewedProductId))
                .as("索引热度键不抄存量 PV（列已删），从默认 0 起").isZero();
        assertThat(indexViewCountOf("product_feed_index", "product_id", legacyProductId))
                .as("无 PV = view_count 0").isZero();
    }

    @Test
    void 跑腿回填只收PUBLISHED且feed_time取通过时刻() {
        Session author = registerAndLogin("13900001502", "跑腿回填作者");

        // 无审批流水的存量老单——feed_time 兜底 create_time
        String legacyErrandId = insertErrand(author.userId(), "PUBLISHED", "6.00");
        // 有审批流水的大厅单——feed_time 取通过时刻
        String approvedErrandId = insertErrand(author.userId(), "PUBLISHED", "8.88");
        insertApproveReview("ERRAND", approvedErrandId, author.userId(), 1, ts(-2));
        // 非 PUBLISHED 不进索引（已付待审/已锁定/已取消）
        String pendingErrandId = insertErrand(author.userId(), "PENDING_REVIEW", "5.00");
        String lockedErrandId = insertErrand(author.userId(), "LOCKED", "3.00");
        String cancelledErrandId = insertErrand(author.userId(), "CANCELLED", "2.00");

        runScript(BACKFILL_RELATIVE);

        assertThat(jdbcTemplate.queryForList("SELECT errand_order_id FROM errand_feed_index", String.class))
                .as("只回填 PUBLISHED 跑腿：待审/锁定/取消不进索引")
                .containsExactlyInAnyOrder(legacyErrandId, approvedErrandId);

        assertThat(queryIndexFeedTime("errand_feed_index", "errand_order_id", approvedErrandId))
                .as("feed_time = 审核通过时刻").isEqualTo(lastApproveTime("ERRAND", approvedErrandId));
        assertThat(queryIndexFeedTime("errand_feed_index", "errand_order_id", legacyErrandId))
                .as("查不到审批流水兜底 create_time")
                .isEqualTo(queryColumnTime("errand_order", "errand_order_id", "create_time", legacyErrandId));

        assertThat(queryIndexPrice("errand_feed_index", "errand_order_id", approvedErrandId))
                .as("price = 悬赏 reward 抄入").isEqualTo("8.88");
        assertThat(indexViewCountOf("errand_feed_index", "errand_order_id", approvedErrandId))
                .as("跑腿无存量浏览量，view_count 恒 0").isZero();
    }

    @Test
    void 脚本可重复执行且重跑追平窗口期漂移() {
        Session author = registerAndLogin("13900001503", "幂等作者");
        // 仍在售的商品：窗口期改价——重跑追平
        String productId = insertProduct(author.userId(), "ON_SALE", "10.00");
        // 离场商品：窗口期售出——收口段同步不可见
        String soldProductId = insertProduct(author.userId(), "ON_SALE", "4.00");
        String errandId = insertErrand(author.userId(), "PUBLISHED", "7.00");

        runScript(BACKFILL_RELATIVE);
        // 首跑后的"窗口期"：旧代码还在写旧表——改价、商品售出、跑腿被锁定
        jdbcTemplate.update("UPDATE product SET price = 66.00 WHERE product_id = ?", productId);
        jdbcTemplate.update("UPDATE product SET state = 'SOLD' WHERE product_id = ?", soldProductId);
        jdbcTemplate.update("UPDATE errand_order SET state = 'LOCKED' WHERE errand_order_id = ?", errandId);

        runScript(BACKFILL_RELATIVE);

        assertThat(queryIndexPrice("product_feed_index", "product_id", productId))
                .as("重跑追平改价漂移").isEqualTo("66.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM product_feed_index WHERE product_id = ?", String.class, soldProductId))
                .as("收口段把离开 ON_SALE 的索引行同步成不可见").isEqualTo("SOLD");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM errand_feed_index WHERE errand_order_id = ?", String.class, errandId))
                .as("收口段把离开 PUBLISHED 的索引行同步成不可见").isEqualTo("LOCKED");

        // 再跑一遍：已对齐的行 UPDATE 落回同值，业务列不动（幂等不动点）
        runScript(BACKFILL_RELATIVE);
        assertThat(queryIndexPrice("product_feed_index", "product_id", productId)).isEqualTo("66.00");
        assertThat(jdbcTemplate.queryForList(
                "SELECT product_id FROM product_feed_index WHERE state = 'ON_SALE'", String.class))
                .as("幂等不动点：商品索引行不再漂移").containsExactly(productId);
        assertThat(jdbcTemplate.queryForList(
                "SELECT errand_order_id FROM errand_feed_index WHERE state = 'PUBLISHED'", String.class))
                .as("幂等不动点：跑腿索引行不再漂移").isEmpty();
    }

    // ---------- 夹具与脚本执行 ----------


    /** 直插一行指定态商品（存量形态；uuid-biz-id 票 05 起业务 id 直插 UUID，应用侧发号） */
    private String insertProduct(String userId, String state, String price) {
        String productId = java.util.UUID.randomUUID().toString();
        // 时间参数 Java 侧算好再绑：ShardingSphere 解析 NOW() - INTERVAL n DAY 会 bad SQL grammar
        Timestamp threeDaysAgo = ts(-3 * 24);
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, ?, '回填存量商品', '存量描述', ?, '驿站', 'BOOKS', ?, ?, ?, "
                        + "DATE_ADD(NOW(), INTERVAL 30 DAY))",
                productId, userId, price, state, threeDaysAgo, threeDaysAgo);
        return productId;
    }

    /** 直插一行指定态跑腿单（存量形态） */
    private String insertErrand(String userId, String state, String reward) {
        String errandId = java.util.UUID.randomUUID().toString();
        Timestamp twoDaysAgo = ts(-2 * 24);
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, time_limit_hours, state, "
                        + "create_time, update_time) "
                        + "VALUES (?, ?, 'EXPRESS_PICKUP', '回填存量单', '驿站', '宿舍', ?, 2, ?, ?, ?)",
                errandId, userId, reward, state, twoDaysAgo, twoDaysAgo);
        return errandId;
    }

    /** 直插一条审核通过流水（重放存量审批历史，造多轮时间差用） */
    private void insertApproveReview(String targetType, String targetId, String operatorId, int round, Timestamp at) {
        jdbcTemplate.update("INSERT INTO content_review (target_type, target_id, author_id, review_round, "
                        + "action, operator_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, 'APPROVE', ?, ?, ?)",
                targetType, targetId, operatorId, round, operatorId, at, at);
    }

    private Timestamp ts(double hoursAgo) {
        return Timestamp.valueOf(LocalDateTime.now().minusMinutes((long) (hoursAgo * 60)));
    }

    private LocalDateTime lastApproveTime(String targetType, String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(create_time) FROM content_review WHERE target_type = ? AND target_id = ? AND action = 'APPROVE'",
                Timestamp.class, targetType, targetId).toLocalDateTime();
    }

    private LocalDateTime queryIndexFeedTime(String table, String idColumn, String id) {
        return queryColumnTime(table, idColumn, "feed_time", id);
    }

    private LocalDateTime queryColumnTime(String table, String idColumn, String column, String id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE " + idColumn + " = ?", Timestamp.class, id)
                .toLocalDateTime();
    }

    private String queryIndexPrice(String table, String idColumn, String id) {
        return jdbcTemplate.queryForObject(
                "SELECT price FROM " + table + " WHERE " + idColumn + " = ?", String.class, id);
    }

    private int indexViewCountOf(String table, String idColumn, String id) {
        return jdbcTemplate.queryForObject(
                "SELECT view_count FROM " + table + " WHERE " + idColumn + " = ?", Integer.class, id);
    }

    /**
     * 对测试库执行脚本文件本身：剥掉 -- 注释行后按分号切语句逐条执行。
     * 脚本不自带 USE（库名由命令行给），所以连到 better_school_test 就灌进测试库。
     */
    private void runScript(String scriptRelative) {
        Path script = Paths.get("..", scriptRelative).toAbsolutePath().normalize();
        if (!Files.exists(script)) {
            script = Paths.get(scriptRelative).toAbsolutePath().normalize();
        }
        assertThat(Files.exists(script)).as("脚本应存在：%s", script).isTrue();
        try {
            String cleaned = Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                    .map(line -> {
                        int comment = line.indexOf("--");
                        return comment >= 0 ? line.substring(0, comment) : line;
                    })
                    .collect(Collectors.joining("\n"));
            for (String statement : cleaned.split(";")) {
                if (!statement.isBlank()) {
                    jdbcTemplate.execute(statement);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("执行脚本失败：" + script, e);
        }
    }
}
