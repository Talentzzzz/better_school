package com.betterschool.integration.moderation;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutCallbackFixture;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领单 + 租约回收 + 放回（票 08）集成测试：审核员一次只拉一条，拉到手上就有 5 分钟租约、
 * 到点由既有超时中心回调自动回池（进入待审时刻不变、等待时长继续累加）；
 * 复合 bizId {taskId}:{claimSeq} 使重领后旧轮次回调零副作用；惰性兜底在回调缺失时也能回收。
 *
 * <p>两个边按真实形状驱动：注册边看 {@code timeout_task}（领单事务内直插、提交即可见——内嵌超时中心票 03）；
 * 回调边直接打进程内分发器 {@code TimeoutTaskCallbackDispatcher}（扫描 case 到点后调的就是它，票 05）。
 * seam = trigger 层 HTTP 入口 + MySQL 可观察状态（review_task / timeout_task 行）。
 */
@DisplayName("票 08：领单原子指派 + 租约回收 + 放回")
class ReviewTaskLeaseIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TimeoutTaskCallbackDispatcher timeoutTaskCallback;

    // ---------- 领单：原子指派 + 起租约 + 注册复合 bizId ----------

    @Test
    void 领单原子指派起五分钟租约并注册复合bizId超时() {
        Session author = registerAndLogin("13800008001", "作者1");
        Session auditor = auditorSession("13900008001");

        String orderId = createPaidOrder(author);
        LocalDateTime claimedAt = LocalDateTime.now();

        JsonNode claimed = claim(auditor, null);
        assertThat(claimed.path("data").path("taskId").asLong()).as("领到一条工单").isPositive();
        long taskId = claimed.path("data").path("taskId").asLong();
        assertThat(claimed.path("data").path("targetType").asText()).isEqualTo("ERRAND");
        assertThat(claimed.path("data").path("targetId").asText()).isEqualTo(orderId);
        assertThat(claimed.path("data").path("claimState").asText()).isEqualTo("CLAIMED");
        assertThat(claimed.path("data").path("claimSeq").asInt()).isEqualTo(1);

        // DB：抢占生效 + 租约 5min 从领单时刻起算 + 进入待审时刻不被领单改动
        Map<String, Object> row = taskRow(taskId);
        assertThat(row.get("claim_state")).isEqualTo("CLAIMED");
        assertThat((String) row.get("claim_by")).isEqualTo(auditor.userId());
        assertThat(((Number) row.get("claim_seq")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("recycle_count")).intValue()).isZero();
        assertThat((LocalDateTime) row.get("lease_expire_at"))
                .as("租约 5min 从领单时刻起算")
                .isBetween(claimedAt.plusMinutes(4), claimedAt.plusMinutes(6));
        assertThat(row.get("entered_at")).as("领单不改进入待审时刻").isEqualTo(enteredAtOf("ERRAND", orderId));

        // 注册行：复合 bizId {taskId}:{claimSeq}、窗口从领单时刻起算、领单事务提交后立即可见
        Map<String, Object> registerRow = registerRowOf(taskId, 1);
        assertThat(registerRow.get("status")).as("注册即 WAITING（无 outbox 中间态）").isEqualTo("WAITING");
        assertThat((LocalDateTime) registerRow.get("action_time"))
                .isBetween(claimedAt.plusMinutes(4), claimedAt.plusMinutes(6));
    }

    @Test
    void 类型只作过滤器一次只取一条池空返回null() {
        Session author = registerAndLogin("13800008002", "作者2");
        Session auditor = auditorSession("13900008002");

        String orderId = createPaidOrder(author);
        String productId = createProduct(author);

        // 类型过滤：POST 池空 → data=null，不串取其他类型的工单
        assertThat(claim(auditor, "POST").path("data").isNull()).isTrue();
        // ERRAND 过滤只取跑腿
        JsonNode errandClaim = claim(auditor, "ERRAND");
        assertThat(errandClaim.path("data").path("targetType").asText()).isEqualTo("ERRAND");
        assertThat(errandClaim.path("data").path("targetId").asText()).isEqualTo(orderId);
        // PRODUCT 过滤取商品
        JsonNode productClaim = claim(auditor, "PRODUCT");
        assertThat(productClaim.path("data").path("targetId").asText()).isEqualTo(productId);
        // 池空（不预取：每次领单只拿一条）
        assertThat(claim(auditor, null).path("data").isNull()).isTrue();
        assertThat(claim(auditor, "ERRAND").path("data").isNull()).isTrue();
    }

    // ---------- 并发领单：条件 UPDATE 抢占，仅一人成功 ----------

    @Test
    void 并发领单同一工单仅一人成功() throws Exception {
        Session author = registerAndLogin("13800008003", "作者3");
        Session auditorA = auditorSession("13900008003");
        Session auditorB = auditorSession("13900008004");
        String orderId = createPaidOrder(author);

        // 两审核员同时抢池内唯一工单：条件 UPDATE 守卫 claim_state='PENDING'，影响行数 1 者赢
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = StreamSupport.stream(List.of(auditorA, auditorB).spliterator(), false)
                .map(auditor -> pool.submit(() -> {
                    start.await();
                    return claim(auditor, null);
                })).toList();
        start.countDown();
        List<JsonNode> responses = new ArrayList<>();
        for (Future<JsonNode> future : futures) {
            responses.add(future.get());
        }
        pool.shutdown();

        long winners = responses.stream().filter(r -> !r.path("data").isNull()).count();
        assertThat(winners).as("并发下恰好一人领到").isEqualTo(1);

        JsonNode winnerResponse = responses.stream().filter(r -> !r.path("data").isNull()).findFirst().orElseThrow();
        long taskId = winnerResponse.path("data").path("taskId").asLong();
        Map<String, Object> row = taskRow(taskId);
        assertThat(row.get("claim_state")).isEqualTo("CLAIMED");
        // 持有人必是两审核员之一，且 claim_seq 恒 1（败者的条件 UPDATE 落空，没把轮次推走）
        String claimBy = (String) row.get("claim_by");
        assertThat(claimBy).isIn(auditorA.userId(), auditorB.userId());
        assertThat(((Number) row.get("claim_seq")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("recycle_count")).intValue()).isZero();
        // 目标工单仍指向同一内容，未被领两次
        assertThat((String) row.get("target_id")).as("目标工单仍指向同一内容，未被领两次").isEqualTo(orderId);
    }

    // ---------- 租约到点回调：自动回池 + entered_at 不变 + 旧轮次零副作用 ----------

    @Test
    void 到点回调自动回池entered_at不变重领后旧轮次回调零副作用() {
        Session author = registerAndLogin("13800008004", "作者4");
        Session auditorA = auditorSession("13900008005");
        Session auditorB = auditorSession("13900008006");
        String orderId = createPaidOrder(author);

        JsonNode firstClaim = claim(auditorA, null);
        long taskId = firstClaim.path("data").path("taskId").asLong();
        LocalDateTime enteredAt = enteredAtOf("ERRAND", orderId);

        // 到点回调：回 PENDING、清持单人、recycle_count+1
        assertCallbackSuccess(callback(leaseTask(taskId, 1)));

        Map<String, Object> recycled = taskRow(taskId);
        assertThat(recycled.get("claim_state")).isEqualTo("PENDING");
        assertThat(recycled.get("claim_by")).isNull();
        assertThat(recycled.get("lease_expire_at")).isNull();
        assertThat(((Number) recycled.get("recycle_count")).intValue()).isEqualTo(1);
        assertThat((LocalDateTime) recycled.get("entered_at"))
                .as("回收不改进入待审时刻（等待时长继续累加）").isEqualTo(enteredAt);
        assertThat(((Number) recycled.get("escalated")).intValue()).isZero();

        // 同轮重投（中心 at-least-once）：轮次守卫未命中＝幂等成功、零副作用
        assertCallbackSuccess(callback(leaseTask(taskId, 1)));
        assertThat(((Number) taskRow(taskId).get("recycle_count")).intValue())
                .as("重复回调不重复计数").isEqualTo(1);

        // 重领：新轮次 seq=2，注册行是**新键**（不被旧键幂等复位）
        JsonNode secondClaim = claim(auditorB, null);
        assertThat(secondClaim.path("data").path("taskId").asLong()).isEqualTo(taskId);
        assertThat(secondClaim.path("data").path("claimSeq").asInt()).isEqualTo(2);
        assertThat(registerRowCount(taskId)).as("两次领单两行独立注册").isEqualTo(2);

        // 旧轮次回调（seq=1）对 seq=2 的租约零副作用
        assertCallbackSuccess(callback(leaseTask(taskId, 1)));
        Map<String, Object> stillClaimed = taskRow(taskId);
        assertThat(stillClaimed.get("claim_state")).as("陈旧轮次回调不得回收新租约").isEqualTo("CLAIMED");
        assertThat((String) stillClaimed.get("claim_by")).isEqualTo(auditorB.userId());
        assertThat(((Number) stillClaimed.get("claim_seq")).intValue()).isEqualTo(2);
        assertThat(((Number) stillClaimed.get("recycle_count")).intValue()).isEqualTo(1);

        // 当前轮次回调才生效，工单重回池中可再领
        assertCallbackSuccess(callback(leaseTask(taskId, 2)));
        assertThat(taskRow(taskId).get("claim_state")).isEqualTo("PENDING");
        assertThat(((Number) taskRow(taskId).get("recycle_count")).intValue()).isEqualTo(2);
        JsonNode thirdClaim = claim(auditorA, null);
        assertThat(thirdClaim.path("data").path("claimSeq").asInt()).isEqualTo(3);
    }

    // ---------- 惰性兜底：回调缺失时领单前回收过期租约 ----------

    @Test
    void 惰性兜底在回调缺失时也能回收过期租约() {
        Session author = registerAndLogin("13800008005", "作者5");
        Session auditorA = auditorSession("13900008007");
        Session auditorB = auditorSession("13900008008");
        String orderId = createPaidOrder(author);

        JsonNode firstClaim = claim(auditorA, null);
        long taskId = firstClaim.path("data").path("taskId").asLong();

        // 模拟中心回调丢失：租约已过期但工单仍卡 CLAIMED
        jdbcTemplate.update("UPDATE review_task SET lease_expire_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?",
                taskId);
        assertThat(taskRow(taskId).get("claim_state")).isEqualTo("CLAIMED");

        // 下一人领单：领单事务内的惰性回收先把过期租约放回池，紧接着本轮 FIFO 又领到同一条
        JsonNode secondClaim = claim(auditorB, null);
        assertThat(secondClaim.path("data").path("taskId").asLong())
                .as("过期租约被惰性放回后被重新领走").isEqualTo(taskId);
        assertThat(secondClaim.path("data").path("claimSeq").asInt()).isEqualTo(2);

        Map<String, Object> row = taskRow(taskId);
        assertThat(row.get("claim_state")).isEqualTo("CLAIMED");
        assertThat((String) row.get("claim_by")).isEqualTo(auditorB.userId());
        assertThat(((Number) row.get("recycle_count")).intValue())
                .as("惰性回收与回调同口径计 recycle_count").isEqualTo(1);
        assertThat(row.get("entered_at")).as("惰性回收同样不改进入待审时刻")
                .isEqualTo(enteredAtOf("ERRAND", orderId));
    }

    // ---------- 主动放回 ----------

    @Test
    void 主动放回退池计一次回收_非持有人与重复放回失败() {
        Session author = registerAndLogin("13800008006", "作者6");
        Session auditorA = auditorSession("13900008009");
        Session auditorB = auditorSession("13900008010");
        String orderId = createPaidOrder(author);

        JsonNode claimResponse = claim(auditorA, null);
        long taskId = claimResponse.path("data").path("taskId").asLong();
        LocalDateTime enteredAt = enteredAtOf("ERRAND", orderId);

        // 持有人放回（带原因）：CLAIMED→PENDING、recycle_count+1
        assertSuccess(postJson("/api/moderation/tasks/" + taskId + "/release",
                Map.of("reason", "素材加载不出来，无法判断"), auditorA), "主动放回");
        Map<String, Object> released = taskRow(taskId);
        assertThat(released.get("claim_state")).isEqualTo("PENDING");
        assertThat(released.get("claim_by")).isNull();
        assertThat(released.get("lease_expire_at")).isNull();
        assertThat(((Number) released.get("recycle_count")).intValue()).isEqualTo(1);
        assertThat((LocalDateTime) released.get("entered_at")).isEqualTo(enteredAt);

        // 非持有人放回 / 对在池工单放回：都打不中持有人守卫，报错
        JsonNode wrongReleaser = postJson("/api/moderation/tasks/" + taskId + "/release", Map.of(), auditorB);
        assertThat(wrongReleaser.path("code").asText()).as("非持有人放回被拒").isNotEqualTo("200");
        JsonNode repeatRelease = postJson("/api/moderation/tasks/" + taskId + "/release", Map.of(), auditorA);
        assertThat(repeatRelease.path("code").asText()).as("重复放回被拒").isNotEqualTo("200");
        assertThat(((Number) taskRow(taskId).get("recycle_count")).intValue()).isEqualTo(1);

        // 放回后工单回池可再领
        JsonNode reclame = claim(auditorB, null);
        assertThat(reclame.path("data").path("taskId").asLong()).isEqualTo(taskId);
        assertThat(reclame.path("data").path("claimSeq").asInt()).isEqualTo(2);
    }

    // ---------- 升级：回收达 3 次置需人工干预标记 ----------

    @Test
    void 回收次数达三次置需人工干预标记() {
        Session author = registerAndLogin("13800008007", "作者7");
        Session auditor = auditorSession("13900008011");
        String orderId = createPaidOrder(author);

        long taskId = claim(auditor, null).path("data").path("taskId").asLong();
        for (int round = 1; round <= 3; round++) {
            assertCallbackSuccess(callback(leaseTask(taskId, round)));
            if (round < 3) {
                assertThat(claim(auditor, null).path("data").path("claimSeq").asInt()).isEqualTo(round + 1);
            }
        }

        Map<String, Object> row = taskRow(taskId);
        assertThat(((Number) row.get("recycle_count")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("escalated")).intValue())
                .as("recycle_count≥3 置需人工干预标记").isEqualTo(1);
        // 标记不改状态机：任务仍在池中可被领走
        assertThat(row.get("claim_state")).isEqualTo("PENDING");
        assertThat(claim(auditor, null).path("data").path("taskId").asLong()).isEqualTo(taskId);
    }

    // ---------- 用例夹具 ----------

    private String createPaidOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "帮忙取个快递");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", 10);
        body.put("timeLimit", "HOURS_2");
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    private String createProduct(Session seller) {
        JsonNode response = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", "待审商品"),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        return response.path("data").asText();
    }

    /** 审核员登录态：注册 → 改库提权 AUDITOR → 重新走验证码登录拿含新角色的 JWT */
    private Session auditorSession(String phone) {
        Session registered = registerAndLogin(phone, "审核员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", phone), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + phone).get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", phone, "code", code), null);
        assertSuccess(loginResponse, "审核员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    /** 领单（targetType 可空 = 跨类型） */
    private JsonNode claim(Session auditor, String targetType) {
        return postJson("/api/moderation/tasks/claim",
                targetType == null ? Map.of() : Map.of("targetType", targetType), auditor);
    }

    // ---------- 回调工具 ----------

    private static TimeoutTaskEntity leaseTask(long taskId, int claimSeq) {
        return TimeoutCallbackFixture.dueTask(Constants.REVIEW_LEASE_TIMEOUT, taskId + ":" + claimSeq, 5);
    }

    private Set<String> callback(TimeoutTaskEntity... tasks) {
        return TimeoutCallbackFixture.callback(timeoutTaskCallback, tasks);
    }

    private static void assertCallbackSuccess(Set<String> failures) {
        TimeoutCallbackFixture.assertCallbackSuccess(failures);
    }

    // ---------- 可观察状态 ----------

    private Map<String, Object> taskRow(long taskId) {
        // escalated 是 TINYINT(1)，驱动按 Boolean 返回——+0 转数值便于统一断言
        return jdbcTemplate.queryForMap(
                "SELECT claim_state, claim_by, claim_seq, lease_expire_at, recycle_count, escalated + 0 AS escalated, "
                        + "entered_at, target_type, target_id FROM review_task WHERE id = ?", taskId);
    }

    private LocalDateTime enteredAtOf(String targetType, String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT entered_at FROM review_task WHERE target_type = ? AND target_id = ?",
                LocalDateTime.class, targetType, targetId);
    }

    private Map<String, Object> registerRowOf(long taskId, int claimSeq) {
        return jdbcTemplate.queryForMap(
                "SELECT action_time, status FROM timeout_task WHERE task_type = ? AND biz_id = ?",
                Constants.REVIEW_LEASE_TIMEOUT, taskId + ":" + claimSeq);
    }

    private int registerRowCount(long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeout_task WHERE task_type = ? AND biz_id LIKE ?",
                Integer.class, Constants.REVIEW_LEASE_TIMEOUT, taskId + ":%");
        return count == null ? 0 : count;
    }

}
