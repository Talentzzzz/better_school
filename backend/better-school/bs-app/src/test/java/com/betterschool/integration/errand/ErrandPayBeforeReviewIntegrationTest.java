package com.betterschool.integration.errand;

import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.ErrandTimeoutScanJob;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿订单先付后审（ADR-0003，票 02）主链路集成测试：
 * 同步初筛命中不落库、创建即待支付、支付事务内转已付款待审 + bs-errand-paid outbox + 资金冻结、
 * 待审期取消解冻、超时扫描对待审单不再有旧语义。
 */
class ErrandPayBeforeReviewIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ErrandTimeoutScanJob errandTimeoutScanJob;

    // ---------- 创建：同步初筛硬拦截 ----------

    @Test
    void 初筛命中创建失败且不落库() {
        Session user = registerAndLogin("13800002001", "发单用户1");
        JsonNode response = postJson("/api/errand/orders", errandBody("帮我代考一下"), user);

        assertThat(response.path("code").asText()).as("初筛命中应业务失败：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).contains("违规词汇");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM errand_order", Integer.class);
        assertThat(count).as("命中订单不应落库").isZero();
    }

    @Test
    void 创建成功进入待支付() {
        Session user = registerAndLogin("13800002002", "发单用户2");
        String orderId = createErrand(user, "帮忙取个快递");

        String status = jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        assertThat(status).as("先付后审：创建即待支付").isEqualTo("PENDING_PAYMENT");
    }

    // ---------- 支付：转待审 + outbox + 冻结 ----------

    @Test
    void 支付成功转已付款待审并落outbox与冻结() {
        Session user = registerAndLogin("13800002003", "发单用户3");
        String orderId = createErrand(user, "帮忙取个快递");

        JsonNode payResponse = postJson("/api/errand/orders/" + orderId + "/pay", null, user);
        assertSuccess(payResponse, "支付");

        String status = jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        assertThat(status).as("支付成功应转已付款待审").isEqualTo("PENDING_REVIEW");

        // outbox 与支付状态变更同事务：一笔待发消息，载荷为订单 id
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? AND state = 0",
                String.class, Constants.ERRAND_PAID_TOPIC);
        assertThat(payloads).as("bs-errand-paid 消息应已落 outbox").hasSize(1);
        assertThat(readTree(payloads.get(0)).path("orderId").asText()).as("载荷 orderId 为 UUID 字符串（uuid-biz-id 票 05）").isEqualTo(orderId);

        // 资金冻结：FROZEN 流水 + frozen_amount
        Integer frozenRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = 'FROZEN'",
                Integer.class, orderId);
        assertThat(frozenRows).as("应有 FROZEN 流水").isEqualTo(1);
        BigDecimal frozenAmount = jdbcTemplate.queryForObject(
                "SELECT frozen_amount FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, user.userId());
        assertThat(frozenAmount).as("冻结金额应等于悬赏").isEqualByComparingTo("10.00");
    }

    @Test
    void 支付幂等重试不重复转态冻结与落消息() {
        Session user = registerAndLogin("13800002004", "发单用户4");
        String orderId = createErrand(user, "帮忙取个快递");
        postJson("/api/errand/orders/" + orderId + "/pay", null, user);

        JsonNode retryResponse = postJson("/api/errand/orders/" + orderId + "/pay", null, user);
        assertSuccess(retryResponse, "支付重试");

        String status = jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        assertThat(status).isEqualTo("PENDING_REVIEW");
        assertThat(countWalletTransactions(orderId, "FROZEN")).as("冻结流水不重复").isEqualTo(1);
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ?", Integer.class, Constants.ERRAND_PAID_TOPIC);
        assertThat(outboxRows).as("outbox 消息不重复").isEqualTo(1);
    }

    // ---------- 取消：待审期解冻 / 待支付不退款 ----------

    @Test
    void 待审期取消转CANCELLED并解冻退款() {
        Session user = registerAndLogin("13800002005", "发单用户5");
        String orderId = createErrand(user, "帮忙取个快递");
        postJson("/api/errand/orders/" + orderId + "/pay", null, user);

        JsonNode cancelResponse = postJson("/api/errand/orders/" + orderId + "/cancel", null, user);
        assertSuccess(cancelResponse, "待审取消");

        String status = jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        assertThat(status).isEqualTo("CANCELLED");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("应有解冻流水").isEqualTo(1);
        BigDecimal frozenAmount = jdbcTemplate.queryForObject(
                "SELECT frozen_amount FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, user.userId());
        assertThat(frozenAmount).as("冻结应清零").isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, user.userId());
        assertThat(balance).as("冻结金额应退回余额").isEqualByComparingTo("1000.00");
    }

    @Test
    void 取消重试解冻流水幂等不重复() {
        Session user = registerAndLogin("13800002006", "发单用户6");
        String orderId = createErrand(user, "帮忙取个快递");
        postJson("/api/errand/orders/" + orderId + "/pay", null, user);
        postJson("/api/errand/orders/" + orderId + "/cancel", null, user);

        // 重试：订单已 CANCELLED（终态），不再触发第二次解冻
        JsonNode retryResponse = postJson("/api/errand/orders/" + orderId + "/cancel", null, user);
        assertThat(retryResponse.path("code").asText()).isEqualTo("500");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("解冻流水不重复").isEqualTo(1);
    }

    @Test
    void 待支付期取消不产生退款流水() {
        Session user = registerAndLogin("13800002007", "发单用户7");
        String orderId = createErrand(user, "帮忙取个快递");

        JsonNode cancelResponse = postJson("/api/errand/orders/" + orderId + "/cancel", null, user);
        assertSuccess(cancelResponse, "待支付取消");

        String status = jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        assertThat(status).isEqualTo("CANCELLED");
        assertThat(countWalletTransactions(orderId, "UNFROZEN"))
                .as("未冻结不产生退款流水").isZero();
    }

    // ---------- 超时扫描：PENDING_REVIEW 无旧语义 ----------

    @Test
    void 超时扫描取消超时待支付单但不动待审单() {
        String payTimeoutId = insertOrderWithAge("超时待支付单", "PENDING_PAYMENT", 20);
        String reviewId = insertOrderWithAge("已付款待审单", "PENDING_REVIEW", 20);

        errandTimeoutScanJob.scan(null);

        assertThat(statusOf(payTimeoutId)).as("待支付超 15min 应被扫描取消").isEqualTo("CANCELLED");
        assertThat(statusOf(reviewId)).as("待审单不再按旧超时语义取消").isEqualTo("PENDING_REVIEW");
    }

    @Test
    void REJECTED终态入枚举() {
        assertThat(ErrandStatusVO.REJECTED).isNotNull();
        assertThat(ErrandStatusVO.from("REJECTED")).isEqualTo(ErrandStatusVO.REJECTED);
    }

    // ---------- 工具 ----------

    private String createErrand(Session user, String description) {
        JsonNode response = postJson("/api/errand/orders", errandBody(description), user);
        assertSuccess(response, "发布跑腿");
        String orderId = response.path("data").asText();
        assertUuidId(orderId);
        return orderId;
    }

    private Map<String, Object> errandBody(String description) {
        return Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", description),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2"));
    }

    private int countWalletTransactions(String orderId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = ?",
                Integer.class, orderId, type);
        return count == null ? 0 : count;
    }

    /** 直插订单（create_time/update_time 回拨 minutesAgo 分钟）；业务 ID 为 UUID（uuid-biz-id 票 05）。
     *  时间回拨在 Java 侧算好当普通 ? 传：`INTERVAL ?` 占位符会被 ShardingSphere 的 SQL 解析弄丢
     *  （内嵌化票 02 起 datasource 坐 SS 驱动），生产代码无此形态，夹具避让即可 */
    private String insertOrderWithAge(String description, String status, int minutesAgo) {
        String orderId = java.util.UUID.randomUUID().toString();
        LocalDateTime aged = LocalDateTime.now().minusMinutes(minutesAgo).truncatedTo(ChronoUnit.SECONDS);
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, pickup_location, "
                        + "delivery_location, reward, time_limit_hours, state, create_time, update_time) "
                        + "VALUES (?, 1, 'EXPRESS_PICKUP', ?, '驿站', '6号楼', 10.00, 2, ?, ?, ?)",
                orderId, description, status, aged, aged);
        return orderId;
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }
}
