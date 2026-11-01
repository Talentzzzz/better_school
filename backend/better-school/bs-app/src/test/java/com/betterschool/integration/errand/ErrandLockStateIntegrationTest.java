package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.ErrandTimeoutScanJob;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿锁单状态机（票 03）集成测试：把「抢 ≠ 开工」落成状态机——
 * 抢单 = PUBLISHED→LOCKED（写 taker_id + lock_seq+1，先到先得）；接单方自己点「确认接单」→ CONFIRMED；
 * 不合适可「放弃」回 PUBLISHED（不扣分、不动幂等位、不改轮次）；锁单期发布方可取消、不可争议；
 * 执行时限到点而单在 LOCKED 时由扫描收口（本轮锁单未开工，不扣接单人分）。
 */
class ErrandLockStateIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ErrandTimeoutScanJob errandTimeoutScanJob;

    // ---------- 抢单：落 LOCKED，写接单人 + 轮次，两端可见 ----------

    @Test
    void 抢单落LOCKED并写接单人与轮次_期间他人不可抢() {
        Session author = registerAndLogin("13800005001", "发单用户1");
        Session taker = registerAndLogin("13800005002", "接单用户1");
        Session other = registerAndLogin("13800005003", "接单用户2");
        long orderId = publishableOrder(author);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");

        assertThat(statusOf(orderId)).as("抢到即锁单，不等于开工").isEqualTo("LOCKED");
        assertThat(takerIdOf(orderId)).as("taker_id 锁单时即写").isEqualTo(taker.userId());
        assertThat(lockSeqOf(orderId)).as("每次抢单 lock_seq +1").isEqualTo(1);
        // 抢单事件类型改名 LOCKED（不再与新「确认接单」事件 CONFIRMED 撞名）
        assertThat(eventRowOf(orderId, "LOCKED"))
                .as("抢单事件：PUBLISHED→LOCKED、操作方 TAKER")
                .containsEntry("from_state", "PUBLISHED")
                .containsEntry("to_state", "LOCKED")
                .containsEntry("actor_role", "TAKER")
                .containsEntry("actor_id", taker.userId());
        // 锁单期间不落搜索同步（搜索侧零改动：已发布单的搜索文档维持旧 PUBLISHED 态）
        assertThat(errandSearchSyncMessageCount()).as("锁单不落搜索同步").isZero();

        // 期间他人不可抢：状态与轮次都不动
        JsonNode secondGrab = postJson("/api/errand/orders/" + orderId + "/grab", null, other);
        assertThat(secondGrab.path("code").asText()).as("已锁单不可再抢：%s", secondGrab).isNotEqualTo("200");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
        assertThat(takerIdOf(orderId)).isEqualTo(taker.userId());
        assertThat(lockSeqOf(orderId)).isEqualTo(1);

        // 两端「我的订单」立刻可见（显示「锁单中」）；作者主页跑腿 tab 同样含 LOCKED
        assertThat(myOrderIds(taker, "take")).as("「我接的单」立即可见").contains(orderId);
        assertThat(myOrderStatus(taker, "take", orderId)).isEqualTo("LOCKED");
        assertThat(myOrderIds(author, "publish")).as("「我发布的」含锁单").contains(orderId);
        assertThat(authorProfileIds(author.userId())).as("作者主页跑腿 tab 含锁单").contains(orderId);
        // 公开列表天然不含锁单（默认 status=PUBLISHED）
        assertThat(publicOrderIds()).doesNotContain(orderId);
    }

    @Test
    void 并发抢单仅一人成功() throws Exception {
        Session author = registerAndLogin("13800005004", "发单用户2");
        Session takerA = registerAndLogin("13800005005", "接单用户3");
        Session takerB = registerAndLogin("13800005006", "接单用户4");
        long orderId = publishableOrder(author);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<JsonNode> responses;
        try {
            Future<JsonNode> first = pool.submit(() -> {
                start.await();
                return postJson("/api/errand/orders/" + orderId + "/grab", null, takerA);
            });
            Future<JsonNode> second = pool.submit(() -> {
                start.await();
                return postJson("/api/errand/orders/" + orderId + "/grab", null, takerB);
            });
            start.countDown();
            responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        long succeeded = responses.stream().filter(response -> "200".equals(response.path("code").asText())).count();
        assertThat(succeeded).as("并发抢单只能一人成功：%s", responses).isEqualTo(1);
        // 条件 UPDATE 先到先得：状态与轮次都只前进一次
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
        assertThat(lockSeqOf(orderId)).as("只有一次成功迁移").isEqualTo(1);
        assertThat(takerIdOf(orderId)).isIn(takerA.userId(), takerB.userId());
    }

    // ---------- 确认接单：仅持单人，LOCKED→CONFIRMED，不改轮次 ----------

    @Test
    void 确认接单仅持单人且不改轮次() {
        Session author = registerAndLogin("13800005007", "发单用户3");
        Session taker = registerAndLogin("13800005008", "接单用户5");
        Session outsider = registerAndLogin("13800005009", "路人");
        long orderId = publishableOrder(author);
        grab(orderId, taker);

        JsonNode byOutsider = postJson("/api/errand/orders/" + orderId + "/accept", null, outsider);
        assertThat(byOutsider.path("code").asText()).as("只有持单人可确认接单：%s", byOutsider).isNotEqualTo("200");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");
        assertThat(statusOf(orderId)).isEqualTo("CONFIRMED");
        assertThat(lockSeqOf(orderId)).as("离开 LOCKED 不改 lock_seq：陈旧回调天然打不中").isEqualTo(1);
        assertThat(eventRowOf(orderId, "CONFIRMED"))
                .as("确认接单事件：LOCKED→CONFIRMED、操作方 TAKER")
                .containsEntry("from_state", "LOCKED")
                .containsEntry("to_state", "CONFIRMED")
                .containsEntry("actor_role", "TAKER")
                .containsEntry("actor_id", taker.userId());
    }

    // ---------- 放弃：回已发布，不扣分 / 不动幂等位 / 不改轮次；他人可再抢 ----------

    @Test
    void 放弃回已发布且不扣分不动幂等位_他人可再抢() {
        Session author = registerAndLogin("13800005010", "发单用户4");
        Session takerA = registerAndLogin("13800005011", "接单用户6");
        Session takerB = registerAndLogin("13800005012", "接单用户7");
        long orderId = publishableOrder(author);
        grab(orderId, takerA);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/abandon", null, takerA), "放弃接单");

        assertThat(statusOf(orderId)).as("放弃回已发布，他人可再抢").isEqualTo("PUBLISHED");
        assertThat(takerIdOf(orderId)).as("放弃清空接单人").isNull();
        assertThat(takerCanceledOf(orderId)).as("放弃不动「接单取消」幂等位").isZero();
        assertThat(lockSeqOf(orderId)).as("放弃不改 lock_seq（轮次留给陈旧回调守卫）").isEqualTo(1);
        assertThat(eventRowOf(orderId, "LOCK_ABANDONED"))
                .as("放弃独立事件：LOCKED→PUBLISHED、原因类型 TAKER_ABANDON")
                .containsEntry("from_state", "LOCKED")
                .containsEntry("to_state", "PUBLISHED")
                .containsEntry("actor_role", "TAKER")
                .containsEntry("actor_id", takerA.userId())
                .containsEntry("reason_type", "TAKER_ABANDON");
        // 放弃是设计内的正常动作：不扣信用分（信用分只记「承诺后违约」）
        assertThat(creditScoreOf(takerA)).as("放弃不扣信用分").isEqualTo(100);
        assertThat(creditDetailCount(takerA, "ERRAND_TAKER_CANCEL")).isZero();
        assertThat(creditDetailCount(takerA, "ERRAND_TIMEOUT")).isZero();

        // 他人可再抢：轮次递增
        grab(orderId, takerB);
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
        assertThat(lockSeqOf(orderId)).as("二次锁单轮次 +1").isEqualTo(2);
        assertThat(takerIdOf(orderId)).isEqualTo(takerB.userId());
    }

    @Test
    void 已确认后接单方取消仍扣分_原路径不变() {
        Session author = registerAndLogin("13800005013", "发单用户5");
        Session taker = registerAndLogin("13800005014", "接单用户8");
        long orderId = publishableOrder(author);
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, taker), "接单方取消");

        assertThat(statusOf(orderId)).as("接单方取消回已发布").isEqualTo("PUBLISHED");
        assertThat(takerCanceledOf(orderId)).as("置「接单取消」幂等位").isEqualTo(1);
        assertThat(creditScoreOf(taker)).as("已承诺后违约：扣 20").isEqualTo(80);
        assertThat(creditDetailCount(taker, "ERRAND_TAKER_CANCEL")).isEqualTo(1);
        assertThat(eventRowOf(orderId, "TAKER_CANCELLED")).containsEntry("from_state", "CONFIRMED")
                .containsEntry("to_state", "PUBLISHED");
    }

    // ---------- 锁单期：发布方可取消（退款、不扣接单方分）／不可争议 ----------

    @Test
    void 锁单期发布方取消退款且不扣接单方分() {
        Session author = registerAndLogin("13800005015", "发单用户6");
        Session taker = registerAndLogin("13800005016", "接单用户9");
        long orderId = publishableOrder(author);
        grab(orderId, taker);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, author), "锁单期发布方取消");

        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("取消解冻全额退款").isEqualTo(1);
        assertThat(creditScoreOf(taker)).as("锁单期取消不是接单方的错，不扣分").isEqualTo(100);
        assertThat(creditDetailCount(taker, "ERRAND_TIMEOUT")).isZero();
    }

    @Test
    void 取货后仍不可取消() {
        Session author = registerAndLogin("13800005021", "发单用户9");
        Session taker = registerAndLogin("13800005022", "接单用户12");
        long orderId = publishableOrder(author);
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pickup", null, taker), "确认取货");
        assertThat(statusOf(orderId)).isEqualTo("PICKED_UP");

        // 取消白名单补 LOCKED 后，取货后仍不可取消（回归：白名单未被放宽）
        JsonNode byAuthor = postJson("/api/errand/orders/" + orderId + "/cancel", null, author);
        JsonNode byTaker = postJson("/api/errand/orders/" + orderId + "/cancel", null, taker);
        assertThat(byAuthor.path("code").asText()).as("取货后发布方不可取消：%s", byAuthor).isNotEqualTo("200");
        assertThat(byTaker.path("code").asText()).as("取货后接单方不可取消：%s", byTaker).isNotEqualTo("200");
        assertThat(statusOf(orderId)).isEqualTo("PICKED_UP");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("未取消即无退款").isZero();
    }

    @Test
    void 锁单期不可发起争议() {
        Session author = registerAndLogin("13800005017", "发单用户7");
        Session taker = registerAndLogin("13800005018", "接单用户10");
        long orderId = publishableOrder(author);
        grab(orderId, taker);

        JsonNode byTaker = postJson("/api/errand/orders/" + orderId + "/dispute", null, taker);
        JsonNode byAuthor = postJson("/api/errand/orders/" + orderId + "/dispute", null, author);
        assertThat(byTaker.path("code").asText()).as("锁单期还没开工，无可争议事实：%s", byTaker).isNotEqualTo("200");
        assertThat(byAuthor.path("code").asText()).isNotEqualTo("200");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
    }

    // ---------- 锁单中被执行时限扫描收口 ----------

    @Test
    void 锁单中被执行时限扫描取消() {
        Session author = registerAndLogin("13800005019", "发单用户8");
        Session taker = registerAndLogin("13800005020", "接单用户11");
        long orderId = publishableOrder(author);
        grab(orderId, taker);

        // 执行时限到点而单仍在 LOCKED：超时扫描的状态集与条件 UPDATE 都含 LOCKED，否则无人收口
        jdbcTemplate.update(
                "UPDATE errand_order SET deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE errand_order_id = ?", orderId);
        errandTimeoutScanJob.scan(null);

        assertThat(statusOf(orderId)).as("锁单中的过期单被扫描取消").isEqualTo("CANCELLED");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("解冻全额退款").isEqualTo(1);
        assertThat(eventRowOf(orderId, "TIMEOUT"))
                .as("超时事件前态取业务行真值 LOCKED")
                .containsEntry("from_state", "LOCKED")
                .containsEntry("to_state", "CANCELLED");
        // 锁单期未开工＝未产生承诺：与「放弃不扣分」同口径，超时也不扣接单人分
        assertThat(creditScoreOf(taker)).isEqualTo(100);
        assertThat(creditDetailCount(taker, "ERRAND_TIMEOUT")).isZero();
    }

    // ---------- 工具 ----------

    /** 走到 PUBLISHED 的完整前置：发布 → 支付冻结 → 人工裁决通过 */
    private long publishableOrder(Session author) {
        JsonNode created = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", "帮忙取个快递"),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2")), author);
        assertSuccess(created, "发布跑腿");
        long orderId = created.path("data").asLong();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        reviewCase.decide("ERRAND", String.valueOf(orderId), true, null, null, "auditor-1");
        assertThat(statusOf(orderId)).as("前置：订单应已发布").isEqualTo("PUBLISHED");
        return orderId;
    }

    private void grab(long orderId, Session taker) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
    }

    private String statusOf(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private String takerIdOf(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT taker_id FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int lockSeqOf(long orderId) {
        Integer lockSeq = jdbcTemplate.queryForObject(
                "SELECT lock_seq FROM errand_order WHERE errand_order_id = ?", Integer.class, orderId);
        return lockSeq == null ? -1 : lockSeq;
    }

    private int takerCanceledOf(long orderId) {
        Integer canceled = jdbcTemplate.queryForObject(
                "SELECT taker_canceled FROM errand_order WHERE errand_order_id = ?", Integer.class, orderId);
        return canceled == null ? -1 : canceled;
    }

    private int creditScoreOf(Session user) {
        Integer score = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM bs_user WHERE user_id = ?", Integer.class, user.userId());
        return score == null ? -1 : score;
    }

    private int creditDetailCount(Session user, String reasonType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_credit_detail WHERE user_id = ? AND reason_type = ?",
                Integer.class, user.userId(), reasonType);
        return count == null ? 0 : count;
    }

    private int countWalletTransactions(long orderId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = ?",
                Integer.class, orderId, type);
        return count == null ? 0 : count;
    }

    /** 跑腿实体（type=errand）的搜索同步消息数：用户注册也会落同步消息，故按载荷过滤 */
    private int errandSearchSyncMessageCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ? AND payload LIKE ?",
                Integer.class, Constants.SEARCH_SYNC_TOPIC, "%\"type\":\"errand\"%");
        return count == null ? 0 : count;
    }

    /** 事件行的五要素（每类事件在一条链路里至多一条，取首条足够） */
    private Map<String, Object> eventRowOf(long orderId, String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, actor_id, actor_role, reason_type "
                        + "FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                orderId, eventType);
    }

    private List<Long> myOrderIds(Session user, String role) {
        JsonNode response = getJson("/api/errand/orders/mine?role=" + role, user);
        assertSuccess(response, "我的跑腿");
        return orderIdsOf(response);
    }

    private String myOrderStatus(Session user, String role, long orderId) {
        JsonNode response = getJson("/api/errand/orders/mine?role=" + role, user);
        assertSuccess(response, "我的跑腿");
        return itemsOf(response).stream()
                .filter(node -> node.path("orderId").asLong() == orderId)
                .map(node -> node.path("status").asText())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("我的列表里没有订单 " + orderId));
    }

    private List<Long> authorProfileIds(String userId) {
        JsonNode response = getJson("/api/errand/users/" + userId + "/orders", null);
        assertSuccess(response, "作者主页跑腿");
        return orderIdsOf(response);
    }

    private List<Long> publicOrderIds() {
        JsonNode response = getJson("/api/errand/orders?page=1&pageSize=20", null);
        assertSuccess(response, "公开跑腿列表");
        return orderIdsOf(response);
    }

    private List<Long> orderIdsOf(JsonNode response) {
        return itemsOf(response).stream().map(node -> node.path("orderId").asLong()).toList();
    }

    private List<JsonNode> itemsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false).toList();
    }
}
