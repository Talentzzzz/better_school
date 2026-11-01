package com.betterschool.integration.timeout;

import com.betterschool.domain.timeout.model.valobj.ScanSummaryVO;
import com.betterschool.infrastructure.sharding.TimeoutTaskShardSlots;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskScanCase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时中心内嵌化票 09 的端到端验收：四个 taskType 各走一遍
 * <b>注册（业务事务内直插）→ 到点 → 扫描认领 → 进程内回调 → 业务状态落定</b>，
 * 外加退避重试与死信两条异常路径。
 *
 * <p>与各域既有测试的分工：本类不再直接驱动分发器，而是驱动<b>真实的扫描 case</b>
 * （{@link TimeoutTaskScanCase}），回调探针保持默认的 DELEGATE，因此回调走的是生产同一条路
 * （{@code TimeoutTaskCallbackDispatcher}）。「到点」用裸 MySQL 把物理行的 {@code next_fire_time}
 * 改到过去来模拟——不 sleep 等真实 deadline（下单/领单的窗口是 15 分钟 / 5 分钟）。
 *
 * <p>四个 taskType 的成功路径全部走真业务动作；退避/死信两条异常路径则用回调探针强制失败——
 * 要让真实业务动作连续失败 16 次只能靠制造数据库故障，不稳定也不该进常驻套件。两条路径的行、扫描、
 * 结算与状态迁移仍是生产同一条路（只有「回调抛异常」这一步被探针替换）。
 *
 * <p>四个业务夹具是从各域既有测试里复制的最小版本（{@code TimeoutTaskCallbackIntegrationTest} /
 * {@code ErrandLockTimeoutIntegrationTest} / {@code ReviewTaskLeaseIntegrationTest}）：抽公共夹具要动
 * 三个已绿测试类，回归面大于收益，故在本类内复制并注明来源。
 */
@DisplayName("票 09：四个 taskType 的注册到落定端到端 + 退避/死信")
class TimeoutTaskEndToEndIntegrationTest extends TimeoutScanTestBase {

    @Autowired
    private TimeoutTaskScanCase timeoutTaskScanCase;

    @Value("${timeout.retry.base-seconds}")
    private long retryBaseSeconds;

    @Value("${timeout.retry.max-times}")
    private int retryMaxTimes;

    // ---------- 四个 taskType：注册 → 到点 → 回调 → 状态落定 ----------

    @Test
    void 商品支付超时_到点回调关单且商品回在售() {
        Session seller = registerAndLogin("13800009001", "端到端卖家");
        Session buyer = registerAndLogin("13800009002", "端到端买家");
        long productId = createApprovedProduct(seller, "端到端相机");
        long orderId = createProductOrder(buyer, productId);
        String bizId = String.valueOf(orderId);

        makeDue(Constants.PRODUCT_ORDER_PAY_TIMEOUT, bizId);
        ScanSummaryVO summary = scanAll();

        assertThat(summary.getSuccessCount()).isGreaterThanOrEqualTo(1);
        assertThat(productOrderStatus(orderId)).as("到点回调执行关单").isEqualTo("CANCELLED");
        assertThat(productStatus(productId)).as("商品回到在售").isEqualTo("ON_SALE");
        assertTaskSuccess(Constants.PRODUCT_ORDER_PAY_TIMEOUT, bizId);
    }

    @Test
    void 跑腿支付超时_到点回调取消订单() {
        Session user = registerAndLogin("13800009003", "端到端跑腿用户");
        long orderId = createErrand(user, "端到端取快递");
        String bizId = String.valueOf(orderId);

        makeDue(Constants.ERRAND_PAY_TIMEOUT, bizId);
        scanAll();

        assertThat(errandStatus(orderId)).as("到点回调取消未支付跑腿单").isEqualTo("CANCELLED");
        assertTaskSuccess(Constants.ERRAND_PAY_TIMEOUT, bizId);
    }

    @Test
    void 跑腿锁单超时_到点回调释放回PUBLISHED并清空接单人() {
        Session author = registerAndLogin("13800009004", "端到端发单用户");
        Session taker = registerAndLogin("13800009005", "端到端接单用户");
        long orderId = publishableOrder(author);
        grab(orderId, taker);
        String bizId = orderId + ":1";

        makeDue(Constants.ERRAND_LOCK_TIMEOUT, bizId);
        scanAll();

        assertThat(errandStatus(orderId)).isEqualTo("PUBLISHED");
        assertThat(takerIdOf(orderId)).as("释放清空接单人").isNull();
        assertTaskSuccess(Constants.ERRAND_LOCK_TIMEOUT, bizId);
    }

    @Test
    void 审核租约超时_到点回调把工单放回池中() {
        Session author = registerAndLogin("13800009006", "端到端作者");
        Session auditor = auditorSession("13900009001");
        long orderId = createPaidOrder(author);
        long taskId = claim(auditor, null).path("data").path("taskId").asLong();
        String bizId = taskId + ":1";

        makeDue(Constants.REVIEW_LEASE_TIMEOUT, bizId);
        scanAll();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT claim_state, claim_by, recycle_count FROM review_task WHERE id = ?", taskId);
        assertThat(row.get("claim_state")).as("到点回收回池").isEqualTo("PENDING");
        assertThat(row.get("claim_by")).isNull();
        assertThat(((Number) row.get("recycle_count")).intValue()).isEqualTo(1);
        assertTaskSuccess(Constants.REVIEW_LEASE_TIMEOUT, bizId);
    }

    // ---------- 两条异常路径：退避重试 / 死信 ----------

    @Test
    void 回调失败进退避重试_三十秒后可再捞且未进死信() {
        String bizId = "e2e-retry-1";
        registerDue(bizId, -1);
        callbackProbe.stubFailure();
        LocalDateTime beforeScan = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        ScanSummaryVO summary = scanAll();

        assertThat(summary.getRetryCount()).isEqualTo(1);
        assertThat(summary.getDeadCount()).isZero();
        Map<String, Object> row = physicalRow(bizId);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(row.get("retry_count")).isEqualTo(1);
        assertThat(dateTime(row.get("next_fire_time")))
                .as("首次失败退避 %ds", retryBaseSeconds)
                .isAfterOrEqualTo(beforeScan.plusSeconds(retryBaseSeconds))
                .isBefore(beforeScan.plusSeconds(retryBaseSeconds + 5));
    }

    @Test
    void 连续失败到上限进死信_离开扫描集合() {
        String bizId = "e2e-dead-1";
        registerDue(bizId, -1);
        // 等它自然失败 16 次要 10 小时：把「已失败次数」摆到上限前一次，本次失败即进死信
        setRetryCount(TASK_TYPE, bizId, retryMaxTimes - 1);
        callbackProbe.stubFailure();

        ScanSummaryVO summary = scanAll();

        assertThat(summary.getDeadCount()).isEqualTo(1);
        Map<String, Object> row = physicalRow(bizId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("retry_count")).as("第 16 次失败照样记账").isEqualTo(retryMaxTimes);

        // 终态行不在下一轮扫描集合里
        ScanSummaryVO second = scanAll();
        assertThat(second.getTaskCount()).isZero();
        assertThat(physicalRow(bizId).get("status")).isEqualTo("FAILED");
    }

    // ---------- 夹具体积：到点 ----------

    /** 把物理行的下次触发时刻改到过去（裸 MySQL，绕开 SS；到点时刻是注册时算的 15min/5min 后） */
    private void makeDue(String taskType, String bizId) {
        String table = TimeoutTaskShardSlots.tableNameOf(slotOf(bizId));
        int updated = physicalUpdate(
                "UPDATE " + table + " SET next_fire_time = ? WHERE task_type = ? AND biz_id = ?",
                LocalDateTime.now().minusMinutes(1), taskType, bizId);
        assertThat(updated).as("注册行应存在：%s/%s（物理表 %s）", taskType, bizId, table).isEqualTo(1);
    }

    private ScanSummaryVO scanAll() {
        // shardTotal = 1：单实例认领全部 16 个槽位（shardTotal = TOTAL_SLOTS 是「每实例认领一个槽位」，
        // 只覆盖 shardIndex 那一张表——不是「扫全部」）
        return timeoutTaskScanCase.scan(0, 1);
    }

    private void assertTaskSuccess(String taskType, String bizId) {
        Map<String, Object> row = physicalRow(taskType, bizId);
        assertThat(row.get("status")).as("%s/%s 应结算为 SUCCESS", taskType, bizId).isEqualTo("SUCCESS");
        assertThat(row.get("retry_count")).isEqualTo(0);
    }

    // ---------- 业务夹具（复制自各域既有测试的最小版本） ----------

    private Session cachedAdminSession;

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录（同 TimeoutTaskCallbackIntegrationTest） */
    private Session adminSession() {
        if (cachedAdminSession == null) {
            cachedAdminSession = roleSession("13900009002", "端到端管理员", "ADMIN");
        }
        return cachedAdminSession;
    }

    /** 发布商品并经管理员裁决通过（下单单前置态 = ON_SALE） */
    private long createApprovedProduct(Session seller, String title) {
        JsonNode publish = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(publish, "发布商品");
        long productId = publish.path("data").asLong();
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), adminSession()), "商品裁决通过");
        return productId;
    }

    private long createProductOrder(Session buyer, long productId) {
        JsonNode response = postJson("/api/trade/orders?productId=" + productId, null, buyer);
        assertSuccess(response, "商品下单");
        return response.path("data").asLong();
    }

    private long createErrand(Session user, String description) {
        JsonNode response = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", description),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2")), user);
        assertSuccess(response, "发布跑腿");
        return response.path("data").asLong();
    }

    /** 走到 PUBLISHED 的完整前置：发布 → 支付 → 人工裁决通过（同 ErrandLockTimeoutIntegrationTest） */
    private long publishableOrder(Session author) {
        long orderId = createErrand(author, "端到端可抢跑腿");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付跑腿");
        reviewCase.decide("ERRAND", String.valueOf(orderId), true, null, null, "auditor-1");
        assertThat(errandStatus(orderId)).as("前置：跑腿应已发布").isEqualTo("PUBLISHED");
        return orderId;
    }

    private void grab(long orderId, Session taker) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertThat(errandStatus(orderId)).isEqualTo("LOCKED");
    }

    /** 已支付跑腿（审核工单入池前置；同 ReviewTaskLeaseIntegrationTest） */
    private long createPaidOrder(Session author) {
        long orderId = createErrand(author, "端到端待审跑腿");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付跑腿");
        return orderId;
    }

    /** 审核员登录态（JWT 里带新角色） */
    private Session auditorSession(String phone) {
        return roleSession(phone, "端到端审核员", "AUDITOR");
    }

    /** 注册 → 改库提权 → 重新走验证码登录：JWT 里才会带新角色 */
    private Session roleSession(String phone, String nickname, String role) {
        Session registered = registerAndLogin(phone, nickname);
        jdbcTemplate.update("UPDATE bs_user SET role = ? WHERE user_id = ?", role, registered.userId());
        postJson("/api/user/send-code", Map.of("phone", phone), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + phone).get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", phone, "code", code), null);
        assertSuccess(loginResponse, "重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    private JsonNode claim(Session auditor, String targetType) {
        return postJson("/api/moderation/tasks/claim",
                targetType == null ? Map.of() : Map.of("targetType", targetType), auditor);
    }

    // ---------- 可观察状态 ----------

    private String productOrderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM product_order WHERE product_order_id = ?", String.class, orderId);
    }

    private String productStatus(long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private String errandStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private String takerIdOf(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT taker_id FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }
}
