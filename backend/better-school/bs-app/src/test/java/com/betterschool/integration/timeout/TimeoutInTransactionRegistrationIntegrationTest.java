package com.betterschool.integration.timeout;

import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandTimeLimitVO;
import com.betterschool.domain.errand.model.valobj.ErrandTypeVO;
import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.infrastructure.adapter.repository.errand.ErrandRepository;
import com.betterschool.infrastructure.adapter.repository.trade.TradeRepository;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 超时中心内嵌化票 03 验收：四个接入方的注册从「业务事务内写注册 outbox + 提交后异步发注册」
 * 换成**业务事务内直插 {@code timeout_task}**——超时行与业务行共用同一条连接、同一个本地事务，
 * 提交即可见（不再有 afterCommit 异步与 30s 扫描兜底），回滚即无行（原子性白送，无幽灵注册）。
 * <p>
 * 同键重入的三分支幂等在超时仓储内裁决（新插入 / 在途同键<b>不改期</b> / 终态复活刷新 action_time），
 * 原 outbox 的「同键复位 PENDING」与「先 cancel 再复位」纪律随注册通道一并消失。
 * <p>
 * seam = trigger 层 HTTP 入口（真实下单/发布链路）+ 仓储直驱（事务边界测试）+ MySQL 可观察状态。
 * 物理清理走裸 MySQL 连接（物理分表经 SS 连接查不到，同 {@code TimeoutShardingRouteIntegrationTest}）。
 */
@DisplayName("票 03：业务事务内直插 timeout_task（同生同死 + 三分支幂等）")
class TimeoutInTransactionRegistrationIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ErrandRepository errandRepository;
    @Autowired
    private TradeRepository tradeRepository;
    @Autowired
    private ITimeoutRepository timeoutRepository;

    @Value("${timeout-it.raw-mysql-url}")
    private String rawMysqlUrl;

    @Value("${spring.datasource.username}")
    private String rawUser;

    @Value("${spring.datasource.password}")
    private String rawPassword;

    // ---------- 提交即可见：真实下单链路，注册行与业务行同一事务落库 ----------

    @Test
    void 两类下单同事务直插timeout_task提交即可见() {
        Session seller = registerAndLogin("13800011001", "卖家1");
        Session buyer = registerAndLogin("13800011002", "买家1");

        String productId = createApprovedProduct(seller, "九成新吉他");
        String orderId = createProductOrder(buyer, productId);
        String errandId = createErrand(buyer, "帮忙取个快递");

        // 业务行与超时行同时可见（同一事务提交），无需等待任何异步注册
        Map<String, Object> productTask = taskRowOf(Constants.PRODUCT_ORDER_PAY_TIMEOUT, orderId);
        assertThat(productTask.get("status")).as("注册即 WAITING（无 outbox 中间态）").isEqualTo("WAITING");
        assertThat((LocalDateTime) productTask.get("action_time"))
                .as("action_time ≈ 下单时刻 + 15min（15 分钟支付超时）")
                .isBetween(LocalDateTime.now().plusMinutes(14), LocalDateTime.now().plusMinutes(16));

        Map<String, Object> errandTask = taskRowOf(Constants.ERRAND_PAY_TIMEOUT, errandId);
        assertThat(errandTask.get("status")).isEqualTo("WAITING");
        assertThat((LocalDateTime) errandTask.get("action_time"))
                .as("action_time ≈ 发布时刻 + 15min")
                .isBetween(LocalDateTime.now().plusMinutes(14), LocalDateTime.now().plusMinutes(16));
    }

    // ---------- 回滚即无行：事务回滚后超时行不落库 ----------

    @Test
    void 业务事务回滚超时行不落库() {
        String tradeOrderId = uniqueUuid();
        String errandOrderId = uniqueUuid();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            // 两个接入方的注册边都在业务事务内被调：这里直接驱动仓储方法复现同一事务边界
            tradeRepository.schedulePayTimeout(tradeOrderId, LocalDateTime.now().plusMinutes(15));
            errandRepository.schedulePayTimeout(errandOrderId, LocalDateTime.now().plusMinutes(15));
            throw new IllegalStateException("业务事务后续步骤失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(taskCountOf(Constants.PRODUCT_ORDER_PAY_TIMEOUT, tradeOrderId))
                .as("回滚时商品支付超时行随事务消失").isZero();
        assertThat(taskCountOf(Constants.ERRAND_PAY_TIMEOUT, errandOrderId))
                .as("回滚时跑腿支付超时行随事务消失").isZero();
    }

    // ---------- 同生同死：同一 TransactionTemplate 里业务行与超时行一起提交 / 一起回滚 ----------

    @Test
    void 同一事务里业务行与超时行同生同死() {
        Session publisher = registerAndLogin("13800011003", "发布者1");

        // 提交侧：跑腿订单行与支付超时行一起落库
        String committedOrderId = transactionTemplate.execute(status -> {
            ErrandOrderEntity orderEntity = newErrandEntity(publisher.userId());
            errandRepository.save(orderEntity);
            errandRepository.schedulePayTimeout(orderEntity.getOrderId(), LocalDateTime.now().plusMinutes(15));
            return orderEntity.getOrderId();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_order WHERE errand_order_id = ?", Integer.class, committedOrderId))
                .as("业务行已提交").isEqualTo(1);
        assertThat(taskCountOf(Constants.ERRAND_PAY_TIMEOUT, committedOrderId))
                .as("同一事务的超时行同批可见").isEqualTo(1);

        // 回滚侧：setRollbackOnly 后业务行与超时行一起消失
        String rolledBackOrderId = transactionTemplate.execute(status -> {
            ErrandOrderEntity orderEntity = newErrandEntity(publisher.userId());
            errandRepository.save(orderEntity);
            errandRepository.schedulePayTimeout(orderEntity.getOrderId(), LocalDateTime.now().plusMinutes(15));
            status.setRollbackOnly();
            return orderEntity.getOrderId();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_order WHERE errand_order_id = ?", Integer.class, rolledBackOrderId))
                .as("业务行随回滚消失").isZero();
        assertThat(taskCountOf(Constants.ERRAND_PAY_TIMEOUT, rolledBackOrderId))
                .as("同一事务的超时行同批消失（同生同死）").isZero();
    }

    // ---------- 三分支幂等：在途不改期 / 终态复活 ----------

    @Test
    void 在途同键重注册幂等成功不改期() {
        String bizId = uniqueUuid();
        try {
            errandRepository.schedulePayTimeout(bizId, LocalDateTime.now().plusMinutes(15));
            // 同键二次注册（原 outbox 语义是「复位 PENDING」；直插后由三分支接管：在途 = 幂等成功不改期）
            errandRepository.schedulePayTimeout(bizId, LocalDateTime.now().plusMinutes(5));

            assertThat(taskCountOf(Constants.ERRAND_PAY_TIMEOUT, bizId))
                    .as("同键重注册不插第二行").isEqualTo(1);
            Map<String, Object> row = taskRowOf(Constants.ERRAND_PAY_TIMEOUT, bizId);
            assertThat((LocalDateTime) row.get("action_time"))
                    .as("在途同键不改期：沿用首次注册的到期时刻（第二次传的 +5min 被吸收）")
                    .isBetween(LocalDateTime.now().plusMinutes(14), LocalDateTime.now().plusMinutes(16));
            assertThat(row.get("status")).isEqualTo("WAITING");
        } finally {
            deleteTimeoutRows(Constants.ERRAND_PAY_TIMEOUT, bizId);
        }
    }

    @Test
    void 终态行复活刷新actionTime() {
        String bizId = uniqueUuid();
        try {
            errandRepository.schedulePayTimeout(bizId, LocalDateTime.now().plusMinutes(15));
            // 到终态（业务取消路径的软置 CANCELED）
            assertThat(timeoutRepository.cancelActive(Constants.ERRAND_PAY_TIMEOUT, bizId)).isTrue();

            // 终态后同键再注册 = 复活重开一轮：action_time 刷新为本次传入的新时刻
            errandRepository.schedulePayTimeout(bizId, LocalDateTime.now().plusMinutes(5));

            Map<String, Object> row = taskRowOf(Constants.ERRAND_PAY_TIMEOUT, bizId);
            assertThat(row.get("status")).as("复活回 WAITING").isEqualTo("WAITING");
            assertThat((LocalDateTime) row.get("action_time"))
                    .as("复活刷新 action_time（+15min 的旧时刻被 +5min 的新时刻取代）")
                    .isBetween(LocalDateTime.now().plusMinutes(4), LocalDateTime.now().plusMinutes(6));
            assertThat(((Number) row.get("retry_count")).intValue()).as("复活清零重试计数").isZero();
        } finally {
            deleteTimeoutRows(Constants.ERRAND_PAY_TIMEOUT, bizId);
        }
    }

    // ---------- 用例夹具 ----------

    private static ErrandOrderEntity newErrandEntity(String publisherId) {
        return ErrandOrderEntity.create(publisherId, ErrandTypeVO.EXPRESS_PICKUP, "帮忙取个快递",
                "菜鸟驿站", "6号楼楼下", BigDecimal.TEN, List.of(), ErrandTimeLimitVO.HOURS_2);
    }

    /** 与真实业务 id 空间隔开的测试专用 UUID：同键断言不撞真实业务行 */
    private static String uniqueUuid() {
        return UUID.randomUUID().toString();
    }

    private String createApprovedProduct(Session seller, String title) {
        JsonNode publish = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(publish, "发布商品");
        String productId = publish.path("data").asText();
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), adminSession()), "商品裁决通过");
        return productId;
    }

    private String createProductOrder(Session buyer, String productId) {
        JsonNode response = postJson("/api/trade/orders?productId=" + productId, null, buyer);
        assertSuccess(response, "商品下单");
        return response.path("data").asText();
    }

    private String createErrand(Session user, String description) {
        JsonNode response = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", description),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2")), user);
        assertSuccess(response, "发布跑腿");
        return response.path("data").asText();
    }

    private Session cachedAdminSession;

    /** 管理员登录态（每用例缓存复用）：先注册（USER），改库提权后重新走验证码登录 */
    private Session adminSession() {
        if (cachedAdminSession != null) {
            return cachedAdminSession;
        }
        Session registered = registerAndLogin("13900011001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900011001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900011001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900011001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        cachedAdminSession = new Session(loginResponse.path("data").path("token").asText(), registered.userId());
        return cachedAdminSession;
    }

    // ---------- timeout_task 断言工具 ----------

    private Map<String, Object> taskRowOf(String taskType, String bizId) {
        // 逻辑表查询经 SS 全表广播合并（读语句无 hint 时的既定行为），键全局唯一故恰命中一行
        return jdbcTemplate.queryForMap(
                "SELECT action_time, status, retry_count FROM timeout_task WHERE task_type = ? AND biz_id = ?",
                taskType, bizId);
    }

    private int taskCountOf(String taskType, String bizId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeout_task WHERE task_type = ? AND biz_id = ?",
                Integer.class, taskType, bizId);
        return count == null ? 0 : count;
    }

    /** 不留痕：物理分表直删（测试行带唯一 bizId，不影响他用例；物理名经 SS 连接查不到，走裸连接） */
    private void deleteTimeoutRows(String taskType, String bizId) {
        try (Connection connection = DriverManager.getConnection(rawMysqlUrl, rawUser, rawPassword)) {
            for (int g = 0; g < 16; g++) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM timeout_task_" + String.format("%02d", g)
                                + " WHERE task_type = ? AND biz_id = ?")) {
                    statement.setString(1, taskType);
                    statement.setString(2, bizId);
                    statement.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("清理 timeout_task 测试行失败", e);
        }
    }
}
