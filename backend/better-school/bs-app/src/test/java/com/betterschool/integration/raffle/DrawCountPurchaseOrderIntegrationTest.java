package com.betterschool.integration.raffle;

import com.betterschool.domain.raffle.model.valobj.DrawCountPurchaseResultVO;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.types.common.AppException;
import com.betterschool.types.common.IdempotencyKeys;
import com.betterschool.usecase.raffle.DrawCountPurchaseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 购买次数链路集成用例（ADR-0014 表形态 + ADR-0016 票 C 三段式）：落单 CREATED → 扣分 →
 * 推进 DONE 三段各自仓储本地事务（「单在 ⇒ 分已扣」降级为「扣分结果未知、扫描收敛」）、
 * Stripe 幂等重放、宕机态被补偿收敛（补扣 + 入账）、死信与人工自愈——购买单（订单兼 outbox）
 * 作为整条链路的台账面。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("抽奖次数 · 购买单最终一致性")
class DrawCountPurchaseOrderIntegrationTest extends TestingEnvironment {

    private static final String USER_ID = "user-2001";
    private static final int PRICE = 100;

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    protected TestRestTemplate restTemplate;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected DrawCountPurchaseCase drawCountPurchaseCase;

    /** 表隔离：购买单 + 两域账务表（points_validity_config 是种子单行，不动） */
    @BeforeEach
    void truncateTables() {
        List.of("draw_count_purchase_order", "draw_count_bucket", "draw_count_detail",
                        "points_bucket", "points_detail", "outbox_message")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
    }

    // ---------- 前置态构造 ----------

    /** 给用户铺一个积分桶（LOTTERY_WIN 来源），返回桶行 id */
    private long insertPointsBucket(String userId, int amount, int remain) {
        jdbcTemplate.update("""
                        INSERT INTO points_bucket (bucket_id, user_id, amount, remain, expire_at, reason_type, ref_id,
                                                   create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                com.betterschool.types.id.UuidIdGenerator.INSTANCE.nextId(),
                userId, amount, remain, java.sql.Timestamp.valueOf(LocalDateTime.now().plusDays(30)),
                "LOTTERY_WIN", "seed-" + userId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM points_bucket WHERE user_id = ?",
                Long.class, userId);
    }

    /** 直接落一行购买单（模拟「扣分事务已提交、入账没跑」的宕机残留等前置态） */
    private void insertPurchaseOrder(String purchaseOrderId, String userId, int quantity, int pointsCost,
                                     int state, int retryCount) {
        jdbcTemplate.update("""
                        INSERT INTO draw_count_purchase_order (purchase_order_id, user_id, quantity, points_cost,
                                                               state, retry_count, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                purchaseOrderId, userId, quantity, pointsCost, state, retryCount);
    }

    private int orderStateOf(String purchaseOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM draw_count_purchase_order WHERE purchase_order_id = ?",
                Integer.class, purchaseOrderId);
    }

    private int orderCountOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_purchase_order WHERE user_id = ?", Integer.class, userId);
    }

    private int pointsRemainOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(remain), 0) FROM points_bucket WHERE user_id = ?", Integer.class, userId);
    }

    private int pointsDetailCountOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM points_detail WHERE user_id = ?", Integer.class, userId);
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("正常购买：三段收敛到 DONE（单在、分已扣、次数已入），入账桶 PURCHASE 来源")
    void purchase_success_commitsOrderAndDeductionTogether() {
        long bucketRowId = insertPointsBucket(USER_ID, 500, 500);

        DrawCountPurchaseResultVO result = drawCountPurchaseCase.purchaseDrawCount(USER_ID, "token-ok", 3);

        assertThat(result.getPointsCost()).isEqualTo(3 * PRICE);
        assertThat(result.getDrawCount()).isEqualTo(3);
        assertThat(orderStateOf(result.getPurchaseOrderId())).as("购买单收敛到 DONE").isEqualTo(1);
        assertThat(pointsRemainOf(USER_ID)).as("积分按份数 × 单价扣减").isEqualTo(500 - 3 * PRICE);
        assertThat(pointsDetailCountOf(USER_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ref_id FROM points_detail WHERE user_id = ?", String.class, USER_ID))
                .as("积分流水引用购买单号（扣分段写入）").isEqualTo(result.getPurchaseOrderId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remain FROM draw_count_bucket WHERE user_id = ?", Integer.class, USER_ID)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason_type FROM draw_count_bucket WHERE user_id = ?", String.class, USER_ID))
                .isEqualTo("PURCHASE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ref_id FROM draw_count_bucket WHERE user_id = ?", String.class, USER_ID))
                .isEqualTo(result.getPurchaseOrderId());
        assertThat(bucketRowId).isPositive();
    }

    @Test
    @DisplayName("积分不足：一分不扣，单即置死信（充值后带原 token 重放自愈）")
    void purchase_insufficient_marksOrderDeadWithoutDeduction() {
        insertPointsBucket(USER_ID, 100, 100);

        assertThatThrownBy(() -> drawCountPurchaseCase.purchaseDrawCount(USER_ID, "token-poor", 3))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getCode()).isEqualTo("4306"));

        assertThat(orderCountOf(USER_ID)).as("落单段先提交（CREATED），扣分段判出不足").isEqualTo(1);
        assertThat(orderStateOf(IdempotencyKeys.of(USER_ID, "token-poor")))
                .as("业务永久失败直接进死信，不占补偿重试").isEqualTo(2);
        assertThat(pointsRemainOf(USER_ID)).as("一分未扣").isEqualTo(100);
        assertThat(pointsDetailCountOf(USER_ID)).isZero();
    }

    @Test
    @DisplayName("同 token 重放（Stripe 语义）：不重复扣分、不重复加次数，返回原结果")
    void purchase_sameToken_replaysWithoutDoubleEffect() {
        insertPointsBucket(USER_ID, 500, 500);

        DrawCountPurchaseResultVO first = drawCountPurchaseCase.purchaseDrawCount(USER_ID, "token-retry", 2);
        DrawCountPurchaseResultVO replay = drawCountPurchaseCase.purchaseDrawCount(USER_ID, "token-retry", 2);

        assertThat(replay.getPurchaseOrderId()).isEqualTo(first.getPurchaseOrderId());
        assertThat(replay.getPointsCost()).isEqualTo(first.getPointsCost());
        assertThat(replay.getDrawCount()).isEqualTo(first.getDrawCount());
        assertThat(orderCountOf(USER_ID)).isEqualTo(1);
        assertThat(pointsRemainOf(USER_ID)).as("积分只扣一次").isEqualTo(500 - 2 * PRICE);
        assertThat(pointsDetailCountOf(USER_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_bucket WHERE user_id = ?", Integer.class, USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("宕机残留（已落单、扣分结果未知）被补偿收敛：补扣 + 入账 + 置 DONE")
    void crashedOrder_convergedByCompensation() {
        insertPointsBucket(USER_ID, 500, 500);
        String orderId = IdempotencyKeys.of(USER_ID, "token-crash");
        // 模拟「落单段已提交、进程在扣分段前宕机」：只留一行 state=0（CREATED）且无扣分流水的单
        insertPurchaseOrder(orderId, USER_ID, 2, 2 * PRICE, 0, 0);

        int completed = drawCountPurchaseCase.compensateDueOrders(100);

        assertThat(completed).isEqualTo(1);
        assertThat(orderStateOf(orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remain FROM draw_count_bucket WHERE ref_id = ?", Integer.class, orderId)).isEqualTo(2);
        assertThat(pointsRemainOf(USER_ID)).as("扣分段幂等补上（此前从未扣过）").isEqualTo(500 - 2 * PRICE);
        assertThat(pointsDetailCountOf(USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("持续失败 5 次进入死信；人工重置后可被客户端重试自愈")
    void deadLetter_afterMaxRetry_thenHealedByReplay() {
        String token = "token-dead";
        String orderId = IdempotencyKeys.of(USER_ID, token);
        // points_cost=0 会撞积分域扣分的参数守卫，模拟「扣分段持续失败」
        insertPurchaseOrder(orderId, USER_ID, 0, 0, 0, 0);

        for (int attempt = 1; attempt <= 5; attempt++) {
            drawCountPurchaseCase.compensateDueOrders(100);
            // 模拟时间流逝：退避时点清零让下一轮扫描立刻可见
            jdbcTemplate.update(
                    "UPDATE draw_count_purchase_order SET next_retry_at = NULL WHERE purchase_order_id = ?", orderId);
        }

        assertThat(orderStateOf(orderId)).as("重试耗尽进死信").isEqualTo(2);
        Integer retryCount = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM draw_count_purchase_order WHERE purchase_order_id = ?", Integer.class, orderId);
        assertThat(retryCount).isEqualTo(5);

        // 人工修复（补上合法的份数）+ 客户端带同 token 重放：死信自愈到 DONE
        jdbcTemplate.update(
                "UPDATE draw_count_purchase_order SET quantity = 1, points_cost = ? WHERE purchase_order_id = ?",
                PRICE, orderId);
        insertPointsBucket(USER_ID, PRICE, PRICE);
        DrawCountPurchaseResultVO healed = drawCountPurchaseCase.purchaseDrawCount(USER_ID, token, 1);

        assertThat(healed.getPurchaseOrderId()).isEqualTo(orderId);
        assertThat(orderStateOf(orderId)).as("死信被重放收敛到 DONE").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remain FROM draw_count_bucket WHERE ref_id = ?", Integer.class, orderId)).isEqualTo(1);
    }
}
