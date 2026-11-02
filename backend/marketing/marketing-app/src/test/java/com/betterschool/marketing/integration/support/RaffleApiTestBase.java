package com.betterschool.marketing.integration.support;

import com.betterschool.marketing.domain.raffle.service.IRaffleShippingService;
import com.betterschool.marketing.domain.raffle.service.warmup.IRaffleWarmupService;
import com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageSender;
import com.betterschool.marketing.trigger.raffle.listener.LotteryShippingConsumer;
import com.betterschool.marketing.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽奖域集成测试基类（票 13 重写）：完整 Spring 上下文，连 scripts/test-env.sh up 备好的常驻栈
 * （bs_marketing_test 库 + redis 逻辑库 1）。
 *
 * <p>约定（照 {@code PointsApiTestBase}）：单一 seam = HTTP 走 trigger 入口、预热/合并走域服务；
 * 断言只看外部可观察状态（HTTP 响应 + MySQL 落库 + redis 桶值）；隔离靠每用例清业务表与
 * {@code bs:raffle:*} / {@code bs:inventory:*} 两段 redis key。登录态直接按共享密钥签 JWT。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class RaffleApiTestBase extends TestingEnvironment {

    /** 与夹具同值的活动窗口：用例改写活动后靠这一档复位 */
    protected static final LocalDateTime ACTIVITY_START = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    protected static final LocalDateTime ACTIVITY_END = LocalDateTime.of(2027, 12, 31, 23, 59, 59);

    protected static final String ACTIVITY_ID = "8801";
    protected static final String STRATEGY_ID = "8802";
    /** 奖品位（单活动单策略）：1 积分档 / 2 实物档 / 3 谢谢参与 / 4 兜底 */
    protected static final String AWARD_POINTS = "8803";
    protected static final String AWARD_BAG = "8804";
    protected static final String AWARD_THANKS = "8805";
    protected static final String AWARD_FALLBACK = "8806";

    /** 库存 / 配额业务 ID */
    protected static final String POINTS_INV_ID = "8811";
    protected static final String QUOTA_INV_ID = "8812";
    protected static final String FALLBACK_INV_ID = "8813";

    /** giveChances 的 ref_id 发号器（每次调用换一个，避免撞 uk_user_reason_ref） */
    private static final AtomicLong REF_ID_SEQ = new AtomicLong(9_000_000L);

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
    protected RedissonClient redissonClient;
    @Autowired
    protected IRaffleWarmupService raffleWarmupService;
    @Autowired
    protected IRaffleShippingService raffleShippingService;
    @Autowired
    protected LotteryShippingConsumer lotteryShippingConsumer;    @Autowired
    protected OutboxMessageSender outboxMessageSender;
    @Autowired
    protected OutboxTransactionProbe outboxTransactionProbe;

    /** 表隔离：清抽奖 + 库存两段业务表，清两段 redis key。配置表由用例自己铺。 */
    @BeforeEach
    void resetState() {
        List.of("draw_activity", "draw_strategy", "draw_strategy_award", "draw_strategy_weight",
                        "draw_record", "draw_record_detail",
                        "draw_count_bucket", "draw_count_detail",
                        "points_bucket", "points_detail",
                        "virtual_inventory_quota", "virtual_inventory_quota_detail",
                        "physical_marketing_quota", "physical_marketing_quota_detail",
                        "outbox_message")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        redissonClient.getKeys().deleteByPattern("bs:raffle:*");
    }

    // ---------- 夹具（配置 + 库存 + 预热）----------

    protected void insertActivity(LocalDateTime begin, LocalDateTime end, String state) {
        // 活动是单行语义（读侧 selectCurrentActivity 取 id DESC LIMIT 1）：同一 activity_id 重铺 = 改窗口/改状态，
        // 先删再插，避免撞 uk_activity_id，也保证新行 id 最大（正是查询要取的那一行）
        jdbcTemplate.update("DELETE FROM draw_activity WHERE activity_id = ?", ACTIVITY_ID);
        jdbcTemplate.update("""
                        INSERT INTO draw_activity (activity_id, activity_name, strategy_id, begin_date_time,
                                                   end_date_time, state, create_time, update_time)
                        VALUES (?, '开学季抽奖', ?, ?, ?, ?, NOW(), NOW())
                        """,
                ACTIVITY_ID, STRATEGY_ID, Timestamp.valueOf(begin), Timestamp.valueOf(end), state);
    }

    protected void insertActivityOpen() {
        insertActivity(ACTIVITY_START, ACTIVITY_END, "open");
    }

    protected void insertStrategy(String rule) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy (strategy_id, strategy_desc, rule, create_time, update_time)
                        VALUES (?, '集成测试策略', ?, NOW(), NOW())
                        """, STRATEGY_ID, rule);
    }

    protected void insertAward(String awardId, String awardName, String awardType, String rate,
                               int level, String invId, String type) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_award (strategy_id, award_id, award_name, award_desc, award_type,
                                                        award_rate, level, inv_id, type, sort, create_time, update_time)
                        VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, 0, NOW(), NOW())
                        """,
                STRATEGY_ID, awardId, awardName, awardType, new java.math.BigDecimal(rate), level, invId, type);
    }

    /** 标准四奖位：积分 0.2 / 实物 0.3 / 谢谢 0.5 + fallback 兜底（不进概率池，rate 随意给 0） */
    protected void insertDefaultAwards() {
        insertAward(AWARD_POINTS, "20 积分", "VIRTUAL", "0.2000", 3, POINTS_INV_ID, "normal");
        insertAward(AWARD_BAG, "校园帆布包", "PHYSICAL", "0.3000", 2, QUOTA_INV_ID, "normal");
        insertAward(AWARD_THANKS, "谢谢参与", "THANKS", "0.5000", 5, "0", "normal");
        insertAward(AWARD_FALLBACK, "5 积分安慰奖", "VIRTUAL", "0.0000", 6, FALLBACK_INV_ID, "fallback");
    }

    protected void insertVirtualInventory(String invId, String name, String type, int value,
                                          long surplusQty, long issuedQty, String state) {
        jdbcTemplate.update("""
                        INSERT INTO virtual_inventory_quota (inv_id, name, type, value, surplus_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """, invId, name, type, value, surplusQty, issuedQty, state);
    }

    protected void insertQuota(String invId, String name, long surplusQty, long waitingQty,
                               long issuedQty, String state) {
        jdbcTemplate.update("""
                        INSERT INTO physical_marketing_quota (inv_id, name, surplus_qty, waiting_qty, issued_qty,
                                                              state, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """, invId, name, surplusQty, waitingQty, issuedQty, state);
    }

    /** 标准库存布局：积分池 surplus 100 / 实物配额 surplus 10 / 兜底权益 surplus 100（台账即真相，无需预热） */
    protected void prepareInventory() {
        insertVirtualInventory(POINTS_INV_ID, "积分发放池", "POINTS", 20, 100L, 0L, "open");
        insertQuota(QUOTA_INV_ID, "实物营销配额", 10L, 0L, 0L, "open");
        insertVirtualInventory(FALLBACK_INV_ID, "兜底权益池", "POINTS", 5, 100L, 0L, "open");
    }

    /** 全套默认场景：活动开 + 默认四奖位 + 权重链 + 库存就绪 + 决策侧预热 */
    protected void prepareDefaultScenario() {
        insertActivityOpen();
        insertStrategy("rule_blacklist,rule_weight");
        insertDefaultAwards();
        prepareInventory();
        raffleWarmupService.warmupByStrategyId(STRATEGY_ID);
    }

    /** 直接塞一个次数桶（模拟兑换入账），供抽奖用例铺前置态 */
    protected void giveChances(String userId, int amount) {
        long refId = REF_ID_SEQ.incrementAndGet();
        String bucketId = com.betterschool.marketing.types.id.UuidIdGenerator.INSTANCE.nextId();
        jdbcTemplate.update("""
                        INSERT INTO draw_count_bucket (bucket_id, user_id, amount, remain, expire_at,
                                                       reason_type, ref_id, create_time, update_time)
                        VALUES (?, ?, ?, ?, DATE_ADD(NOW(), INTERVAL 30 DAY), 'REDEEM', ?, NOW(), NOW())
                        """, bucketId, userId, amount, amount, refId);
    }

    // ---------- 登录态 ----------

    protected String tokenOf(String userId) {
        return TestingEnvironment.tokenOf(userId, "USER");
    }

    protected String adminTokenOf(String userId) {
        return TestingEnvironment.tokenOf(userId, "ADMIN");
    }

    // ---------- HTTP ----------

    protected ResponseEntity<String> get(String url, String userId) {
        return TestingEnvironment.get(restTemplate, url, userId);
    }

    protected ResponseEntity<String> getAnonymous(String url) {
        return get(url, null);
    }

    protected JsonNode getJson(String url, String userId) {
        return TestingEnvironment.getJson(restTemplate, objectMapper, url, userId);
    }

    protected ResponseEntity<String> post(String url, String bearerToken, String jsonBody) {
        return TestingEnvironment.post(restTemplate, url, bearerToken, jsonBody);
    }

    protected JsonNode postJson(String url, String bearerToken, String jsonBody) {
        ResponseEntity<String> response = post(url, bearerToken, jsonBody);
        assertThat(response.getStatusCode().value())
                .as("HTTP 应为 200：POST %s → %s", url, response.getBody())
                .isEqualTo(200);
        return TestingEnvironment.readJson(objectMapper, response.getBody());
    }

    protected JsonNode readJson(String body) {
        return TestingEnvironment.readJson(objectMapper, body);
    }

    /** 抽一次（走 HTTP，贴近前端真实调用）；token 是前端 uuid，重放带同一个 */
    protected JsonNode draw(String userId, String token) {
        return postJson("/api/lottery/draw", tokenOf(userId), "{\"token\":\"" + token + "\"}");
    }

    protected JsonNode activityInfo() {
        return readJson(getAnonymous("/api/lottery/activity").getBody());
    }

    protected JsonNode prizeCatalog() {
        return readJson(getAnonymous("/api/lottery/prizes").getBody());
    }

    protected JsonNode drawChances(String userId) {
        return getJson("/api/lottery/draw-chances", userId);
    }

    protected JsonNode records(String userId, int page, int pageSize) {
        return getJson("/api/lottery/records?page=" + page + "&pageSize=" + pageSize, userId);
    }

    protected JsonNode myPrizes(String userId, String status) {
        String query = status == null ? "" : "status=" + status + "&";
        return getJson("/api/lottery/my-prizes?" + query + "page=1&pageSize=10", userId);
    }

    protected JsonNode exchangeDrawCount(String userId, String token) {
        return postJson("/api/lottery/exchange/draw-count", tokenOf(userId), "{\"token\":\"" + token + "\"}");
    }

    /** 确认收货（票 14）：走 HTTP 入口，{@code id} = draw_record.record_id */
    protected JsonNode confirmReceived(String userId, String recordId) {
        return postJson("/api/lottery/my-prizes/" + recordId + "/receive", tokenOf(userId), "{}");
    }

    // ---------- 发货闭环（票 14）----------

    /**
     * 投一条 {@code bs-lottery-drawn}：构造 broker 会投递的那份 JSON 再走消费者真实解析路径
     * （测试栈没有 broker，消费侧当普通 Bean 调用；真 MQ 链路归联调）。自动从库里补齐奖品字段。
     */
    protected void publishLotteryDrawn(String recordId) {
        lotteryShippingConsumer.onMessage(drawnPayloadOf(recordId));
    }

    /** 手搓一条中奖事件（记录不存在等场景用不了「从库里读字段」的那条路） */
    protected void publishLotteryDrawnRaw(String recordId, String userId, String prizeType, Integer pointsAmount) {
        lotteryShippingConsumer.onMessage(
                "{\"recordId\":%s,\"userId\":\"%s\",\"prizeId\":1,\"prizeType\":\"%s\",\"pointsAmount\":%s}"
                        .formatted(recordId, userId, prizeType, pointsAmount == null ? "null" : pointsAmount));
    }

    /** 与 outbox 里那份同形状的 JSON（从 draw_record 现读字段，避免用例各写一份字面量） */
    protected String drawnPayloadOf(String recordId) {
        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT user_id, award_id, award_type, inv_id FROM draw_record WHERE record_id = ?", recordId);
        Integer pointsAmount = record.get("inv_id") == null ? null
                : jdbcTemplate.query("SELECT value FROM virtual_inventory_quota WHERE inv_id = ? AND type = 'POINTS'",
                        rs -> rs.next() ? rs.getInt(1) : null, record.get("inv_id"));
        // uuid-biz-id 票 07：recordId / prizeId 已是 UUID 字符串，payload 里按字符串（带引号）序列化
        return "{\"recordId\":\"%s\",\"userId\":\"%s\",\"prizeId\":\"%s\",\"prizeType\":\"%s\",\"pointsAmount\":%s}"
                .formatted(recordId, record.get("user_id"),
                        record.get("award_id"), record.get("award_type"),
                        pointsAmount == null ? "null" : pointsAmount);
    }

    protected String stateOf(String recordId) {
        return jdbcTemplate.queryForObject("SELECT state FROM draw_record WHERE record_id = ?", String.class, recordId);
    }

    protected int detailCountOf(String recordId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_record_detail WHERE record_id = ? AND type = ?", Integer.class,
                recordId, type);
        return count == null ? 0 : count;
    }

    /** 某 destination 的 outbox 载荷（落库即可观察：测试栈无 broker，发送必然失败） */
    protected List<String> outboxPayloads(String destination) {
        return jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? ORDER BY id", String.class, destination);
    }

    protected int outboxCount(String destination) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ?", Integer.class, destination);
        return count == null ? 0 : count;
    }

    /** 中奖事件之外不应再有别的 outbox 行（库存合并消息已随票 08 退休，确认不再产生第二步） */
    protected int outboxCountOtherThanLotteryDrawn() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination <> ?",
                Integer.class, Constants.LOTTERY_DRAWN_TOPIC);
        return count == null ? 0 : count;
    }

    protected Map<String, Object> quotaOf(String invId) {
        return jdbcTemplate.queryForMap(
                "SELECT surplus_qty, waiting_qty, issued_qty FROM physical_marketing_quota WHERE inv_id = ?", invId);
    }

    /** outbox 某 destination 的唯一条载荷（不是恰好一条即断言失败） */
    protected JsonNode onlyOutboxEvent(String destination) {
        List<String> payloads = outboxPayloads(destination);
        assertThat(payloads).as("%s 应有且只有一条事件", destination).hasSize(1);
        return readJson(payloads.get(0));
    }

    protected Map<String, Object> outboxRow(String destination) {
        return jdbcTemplate.queryForMap(
                "SELECT msg_id, destination, state, retry_count, update_time FROM outbox_message"
                        + " WHERE destination = ? ORDER BY id DESC LIMIT 1", destination);
    }

    protected int outboxState(long id) {
        Integer state = jdbcTemplate.queryForObject(
                "SELECT state FROM outbox_message WHERE id = ?", Integer.class, id);
        return state == null ? -1 : state;
    }

    /** 轮询等待条件成立（outbox 状态标记与 MQ 回调都是异步线程，断言不能立刻下） */
    protected void awaitTrue(String what, java.time.Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as("等待超时：%s", what).isTrue();
    }

    // ---------- 落库 / redis 断言 ----------

    protected int drawRecordCountOf(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_record WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    protected Map<String, Object> lastDrawRecordOf(String userId) {
        return jdbcTemplate.queryForMap(
                "SELECT record_id, activity_id, strategy_id, award_id, award_name, award_type, level, inv_id, state"
                        + " FROM draw_record WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
    }

    protected int drawDetailCountOf(String recordId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_record_detail WHERE record_id = ?", Integer.class, recordId);
        return count == null ? 0 : count;
    }

    /** 预扣明细行数（state 列已随 D9 形态删除：明细存在即已提交，不再分未合并/已合并） */
    protected int virtualDetailCount(String invId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM virtual_inventory_quota_detail WHERE inv_id = ?",
                Integer.class, invId);
        return count == null ? 0 : count;
    }

    protected int quotaDetailCount(String invId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM physical_marketing_quota_detail WHERE inv_id = ?",
                Integer.class, invId);
        return count == null ? 0 : count;
    }

    /** 虚拟台账已占用份数（票 06 起 Redis 桶消失，台账三列是唯一真相） */
    protected long virtualIssuedQtyOf(String invId) {
        Long issued = jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM virtual_inventory_quota WHERE inv_id = ?", Long.class, invId);
        return issued == null ? 0 : issued;
    }

    protected int remainingChancesOf(String userId) {
        Integer remain = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(remain), 0) FROM draw_count_bucket WHERE user_id = ? AND remain > 0"
                        + " AND expire_at > NOW()", Integer.class, userId);
        return remain == null ? 0 : remain;
    }

    /** 黑名单用户集合由风控侧写入（本域无黑名单用户表），用例自造 */
    protected void blacklistUser(String strategyId, String userId) {
        redissonClient.getBucket("bs:raffle:blacklist:user:" + strategyId + ":" + userId,
                org.redisson.client.codec.StringCodec.INSTANCE).set("exist");
    }

    protected void assertInt(Object actual, int expected, String what) {
        assertThat(((Number) actual).intValue()).as(what).isEqualTo(expected);
    }
}
