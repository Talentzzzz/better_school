package com.betterschool.integration.errand;

import com.betterschool.infrastructure.sharding.TimeoutTaskShardSlots;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskScanCase;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
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
 * 执行时限到点而单在 LOCKED 时由超时中心收口（本轮锁单未开工，不扣接单人分）。
 */
class ErrandLockStateIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TimeoutTaskScanCase timeoutTaskScanCase;

    // ---------- 抢单：落 LOCKED，写接单人 + 轮次，两端可见 ----------

    @Test
    void 抢单落LOCKED并写接单人与轮次_期间他人不可抢() {
        Session author = registerAndLogin("13800005001", "发单用户1");
        Session taker = registerAndLogin("13800005002", "接单用户1");
        Session other = registerAndLogin("13800005003", "接单用户2");
        String orderId = publishableOrder(author);

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
        String orderId = publishableOrder(author);

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
        String orderId = publishableOrder(author);
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

    // ---------- 放弃：回已发布，不扣分 / 不落取消事件 / 不改轮次；他人可再抢 ----------

    @Test
    void 放弃回已发布且不扣分不落取消事件_他人可再抢() {
        Session author = registerAndLogin("13800005010", "发单用户4");
        Session takerA = registerAndLogin("13800005011", "接单用户6");
        Session takerB = registerAndLogin("13800005012", "接单用户7");
        String orderId = publishableOrder(author);
        grab(orderId, takerA);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/abandon", null, takerA), "放弃接单");

        assertThat(statusOf(orderId)).as("放弃回已发布，他人可再抢").isEqualTo("PUBLISHED");
        assertThat(takerIdOf(orderId)).as("放弃清空接单人").isNull();
        assertThat(countEventsOf(orderId, "TAKER_CANCELLED")).as("放弃不落「接单取消」事件（扣分只认该事件）").isZero();
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
        String orderId = publishableOrder(author);
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, taker), "接单方取消");

        assertThat(statusOf(orderId)).as("接单方取消回已发布").isEqualTo("PUBLISHED");
        assertThat(latestEventOf(orderId))
                .as("取消事件落在事件链最末（最新事件 = 本人的 TAKER_CANCELLED，重试幂等判定事实源）")
                .containsEntry("event_type", "TAKER_CANCELLED")
                .containsEntry("actor_id", taker.userId());
        assertThat(creditScoreOf(taker)).as("已承诺后违约：扣 20").isEqualTo(80);
        assertThat(creditDetailCount(taker, "ERRAND_TAKER_CANCEL")).isEqualTo(1);
        assertThat(eventRowOf(orderId, "TAKER_CANCELLED")).containsEntry("from_state", "CONFIRMED")
                .containsEntry("to_state", "PUBLISHED");
    }

    // ---------- 多轮取消：取消→再抢→再取消（事件链幂等判定，替代原 taker_canceled 幂等位） ----------

    @Test
    void 接单方取消后再抢再取消_两轮各自成功_重试零副作用() {
        Session author = registerAndLogin("13800006001", "发单用户多轮");
        Session taker = registerAndLogin("13800006002", "接单用户多轮");
        String orderId = publishableOrder(author);

        // 第一轮：确认后取消
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, taker), "第一次取消");
        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");

        // 第二轮：再抢再取消——旧幂等位若不清理，这一步会被残留标志卡死
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "再次确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, taker), "第二次取消");
        assertThat(statusOf(orderId)).as("两轮取消后订单仍在待抢").isEqualTo("PUBLISHED");
        assertThat(countEventsOf(orderId, "TAKER_CANCELLED")).as("两轮取消各落一条事件").isEqualTo(2);

        // 第二轮取消的重试：最新事件 = 本人 TAKER_CANCELLED 且订单回 PUBLISHED → 幂等成功、零副作用
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, taker), "取消重试");
        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");
        assertThat(countEventsOf(orderId, "TAKER_CANCELLED")).as("重试不再追加事件").isEqualTo(2);
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("重试不触发退款").isZero();

        // 重试再走一次扣分入账，credit 流水 uk_user_reason_ref 去重：一单一违约记录，只扣一次
        assertThat(creditScoreOf(taker)).as("同单多次取消按流水唯一键只扣一次").isEqualTo(80);
        assertThat(creditDetailCount(taker, "ERRAND_TAKER_CANCEL")).isEqualTo(1);
    }

    @Test
    void 接单方取消后他人抢单_原接单人重试不放行_新接单人可正常取消() {
        Session author = registerAndLogin("13800006003", "发单用户换人");
        Session takerA = registerAndLogin("13800006004", "接单用户甲");
        Session takerB = registerAndLogin("13800006005", "接单用户乙");
        String orderId = publishableOrder(author);

        grab(orderId, takerA);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, takerA), "确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, takerA), "甲取消");

        // 乙接手一轮：甲的旧重试进来，最新事件是乙的抢单/取消而非甲的取消 → 拒绝
        grab(orderId, takerB);
        assertThat(postJson("/api/errand/orders/" + orderId + "/cancel", null, takerA).path("code").asText())
                .as("甲已非订单双方，重试不放行").isNotEqualTo("200");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, takerB), "乙确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, takerB), "乙取消不受甲的历史影响");
        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");

        // 乙自己的重试 → 幂等成功
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, takerB), "乙的取消重试");
        assertThat(countEventsOf(orderId, "TAKER_CANCELLED")).as("甲乙各一条").isEqualTo(2);
        assertThat(creditDetailCount(takerA, "ERRAND_TAKER_CANCEL")).isEqualTo(1);
        assertThat(creditDetailCount(takerB, "ERRAND_TAKER_CANCEL")).isEqualTo(1);
    }

    @Test
    void 取消带请求编号_同编号重试零执行_新编号正常执行() {
        Session author = registerAndLogin("13800006006", "发单用户请求编号");
        Session taker = registerAndLogin("13800006007", "接单用户请求编号");
        String orderId = publishableOrder(author);
        String retryId = "req-aaaa-1111";

        // 首次取消（带请求编号）：正常执行
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel?requestId=" + retryId, null, taker), "首次取消");
        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");
        assertThat(cancelRequestIdOf(orderId)).as("事件随行落请求编号").isEqualTo(retryId);

        // 情况三窗口：甲重抢并再确认，此时同编号的旧重试姗姗来迟
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "重新确认接单");
        assertThat(statusOf(orderId)).as("前置：新一轮已确认").isEqualTo("CONFIRMED");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel?requestId=" + retryId, null, taker), "同编号重试");
        assertThat(statusOf(orderId)).as("重放不执行业务：新一轮未被误取消").isEqualTo("CONFIRMED");
        assertThat(countEventsOf(orderId, "TAKER_CANCELLED")).as("重放不追加事件").isEqualTo(1);
        assertThat(creditScoreOf(taker)).as("重放不重复扣分").isEqualTo(80);

        // 新编号 = 新的一次点击：正常执行第二轮取消
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel?requestId=req-bbbb-2222", null, taker), "新编号取消");
        assertThat(statusOf(orderId)).as("新一轮正常取消").isEqualTo("PUBLISHED");
        assertThat(countEventsOf(orderId, "TAKER_CANCELLED")).isEqualTo(2);
        assertThat(creditDetailCount(taker, "ERRAND_TAKER_CANCEL")).as("同单流水唯一键只记一次违约").isEqualTo(1);
    }

    // ---------- 锁单期：发布方可取消（退款、不扣接单方分）／不可争议 ----------

    @Test
    void 锁单期发布方取消退款且不扣接单方分() {
        Session author = registerAndLogin("13800005015", "发单用户6");
        Session taker = registerAndLogin("13800005016", "接单用户9");
        String orderId = publishableOrder(author);
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
        String orderId = publishableOrder(author);
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
        String orderId = publishableOrder(author);
        grab(orderId, taker);

        JsonNode byTaker = postJson("/api/errand/orders/" + orderId + "/dispute", null, taker);
        JsonNode byAuthor = postJson("/api/errand/orders/" + orderId + "/dispute", null, author);
        assertThat(byTaker.path("code").asText()).as("锁单期还没开工，无可争议事实：%s", byTaker).isNotEqualTo("200");
        assertThat(byAuthor.path("code").asText()).isNotEqualTo("200");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
    }

    // ---------- 锁单中被执行时限扫描收口 ----------

    @Test
    void 锁单中被执行时限超时回调取消() {
        Session author = registerAndLogin("13800005019", "发单用户8");
        Session taker = registerAndLogin("13800005020", "接单用户11");
        String orderId = publishableOrder(author);
        grab(orderId, taker);

        // 执行时限到点而单仍在 LOCKED：markTimeout 的状态集含 LOCKED，否则无人收口。
        // 触发路径 = 超时中心：业务行的 deadline 回拨（过 markTimeout 的 deadline 校验与早到守卫）
        // + timeout_task 行的 next_fire_time 回拨（注册时刻按原 deadline 落库，不改期不会到点）
        jdbcTemplate.update(
                "UPDATE errand_order SET deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE errand_order_id = ?", orderId);
        expireTimeoutTask(Constants.ERRAND_ORDER_TIMEOUT, orderId);
        scanAllTimeoutTasks();

        assertThat(statusOf(orderId)).as("锁单中的过期单被超时回调取消").isEqualTo("CANCELLED");
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

    /** 把某条超时任务的到期时刻拉回过去（next_fire_time 注册时按业务时限落库，测试不真等） */
    private void expireTimeoutTask(String taskType, String bizId) {
        int slot = TimeoutTaskShardSlots.slotOf(bizId);
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.addDatabaseShardingValue("timeout_task", slot);
            hintManager.addTableShardingValue("timeout_task", slot);
            jdbcTemplate.update(
                    "UPDATE timeout_task SET next_fire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                            + "WHERE task_type = ? AND biz_id = ?", taskType, bizId);
        }
    }

    /** 跑一轮全槽位超时扫描（单实例认领全部 16 槽，shardTotal=1） */
    private void scanAllTimeoutTasks() {
        timeoutTaskScanCase.scan(0, 1);
    }

    /** 走到 PUBLISHED 的完整前置：发布 → 支付冻结 → 人工裁决通过 */
    private String publishableOrder(Session author) {
        JsonNode created = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("title", "帮忙取个快递"),
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", "帮忙取个快递"),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("deadline", LocalDateTime.now().plusHours(2).toString())), author);
        assertSuccess(created, "发布跑腿");
        String orderId = created.path("data").asText();
        assertUuidId(orderId);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        assertThat(statusOf(orderId)).as("前置：订单应已发布").isEqualTo("PUBLISHED");
        return orderId;
    }

    private void grab(String orderId, Session taker) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private String takerIdOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT taker_id FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int lockSeqOf(String orderId) {
        Integer lockSeq = jdbcTemplate.queryForObject(
                "SELECT lock_seq FROM errand_order WHERE errand_order_id = ?", Integer.class, orderId);
        return lockSeq == null ? -1 : lockSeq;
    }

    /** 同订单同类型事件数（多轮取消会落多条 TAKER_CANCELLED，计数用） */
    private int countEventsOf(String orderId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_order_event WHERE order_id = ? AND event_type = ?",
                Integer.class, orderId, eventType);
        return count == null ? 0 : count;
    }

    /** 首条接单取消事件上的请求编号（未带编号取消为 null） */
    private String cancelRequestIdOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_id FROM errand_order_event WHERE order_id = ? AND event_type = 'TAKER_CANCELLED' "
                        + "ORDER BY event_seq LIMIT 1", String.class, orderId);
    }

    /** 事件链最末一条（event_seq 最大 = 业务上最新发生）：幂等判定与断言事实源 */
    private Map<String, Object> latestEventOf(String orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT event_seq, event_type, from_state, to_state, actor_id, actor_role, reason_type "
                        + "FROM errand_order_event WHERE order_id = ? ORDER BY event_seq DESC LIMIT 1", orderId);
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

    private int countWalletTransactions(String orderId, String type) {
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

    /** 事件行的五要素（每类事件在一条链路里至多一条，取首条足够；顺序只认 event_seq，自增 id 无业务含义） */
    private Map<String, Object> eventRowOf(String orderId, String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, actor_id, actor_role, reason_type "
                        + "FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY event_seq LIMIT 1",
                orderId, eventType);
    }

    private List<String> myOrderIds(Session user, String role) {
        JsonNode response = getJson("/api/errand/orders/mine?role=" + role, user);
        assertSuccess(response, "我的跑腿");
        return orderIdsOf(response);
    }

    private String myOrderStatus(Session user, String role, String orderId) {
        JsonNode response = getJson("/api/errand/orders/mine?role=" + role, user);
        assertSuccess(response, "我的跑腿");
        return itemsOf(response).stream()
                .filter(node -> node.path("orderId").asText().equals(orderId))
                .map(node -> node.path("status").asText())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("我的列表里没有订单 " + orderId));
    }

    private List<String> authorProfileIds(String userId) {
        JsonNode response = getJson("/api/errand/users/" + userId + "/orders", null);
        assertSuccess(response, "作者主页跑腿");
        return orderIdsOf(response);
    }

    private List<String> publicOrderIds() {
        JsonNode response = getJson("/api/errand/orders?page=1&pageSize=20", null);
        assertSuccess(response, "公开跑腿列表");
        return orderIdsOf(response);
    }

    private List<String> orderIdsOf(JsonNode response) {
        return itemsOf(response).stream().map(node -> node.path("orderId").asText()).toList();
    }

    private List<JsonNode> itemsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false).toList();
    }
}
