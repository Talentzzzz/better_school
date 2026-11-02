package com.betterschool.marketing.integration.raffle;

import com.betterschool.marketing.integration.support.RaffleApiTestBase;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.common.IdempotencyKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽奖主链路全链路用例（票 13 验收 2/3/4，走 HTTP trigger 入口）：
 * 次数扣减 → 责任链 → 库存预扣 → draw_record + draw_record_detail(DRAW) 同事务落库 → 返回中奖结果。
 * 覆盖：正常中奖（虚拟/实物/谢谢参与）、次数不足零副作用、黑名单拦截、配额不足走兜底、
 * 同 token 幂等重放、同 token 并发双击（票 03：明细唯一键 → 净扣减只有一份）、预热缺失 DB 直算降级。
 */
public class RaffleDrawPipelineIntegrationTest extends RaffleApiTestBase {

    private static final String USER = "user-3001";
    private static final String TOKEN = "b6d4f0a0-1111-4bbb-8ccc-000000000001";

    /** 把概率变成确定性：全部 normal 档归零后只留目标档 1.0，重预热即必中该档 */
    private void onlyAwardRate(String awardId) {
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 0 WHERE type = 'normal'");
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 1 WHERE award_id = ?", awardId);
        raffleWarmupService.warmupByStrategyId(STRATEGY_ID);
    }

    /**
     * 并发用例的前置：把「这一笔已扣过次数」的事实行先铺好。
     * 本用例要验证的是**库存预扣的幂等**（票 03），不是扣次并发（那是票 13 的 uk_bucket_ref）；
     * 若让两线程同时走扣次，败者在赢家落 draw_record 之前可能拿到 4303，用例会假红。
     */
    private void preConsumeDrawCount(String userId, String token) {
        String recordId = IdempotencyKeys.of(userId, token);
        Long bucketId = jdbcTemplate.queryForObject(
                "SELECT bucket_id FROM draw_count_bucket WHERE user_id = ?", Long.class, userId);
        jdbcTemplate.update("""
                        INSERT INTO draw_count_detail (user_id, bucket_id, ref_id, create_time, update_time)
                        VALUES (?, ?, ?, NOW(), NOW())
                        """,
                userId, bucketId, recordId);
    }

    /** 两线程同时发同一 token 的抽奖请求；返回两份响应（断言都成功） */
    private List<JsonNode> drawConcurrently(String userId, String token) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return post("/api/lottery/draw", tokenOf(userId), "{\"token\":\"" + token + "\"}");
                }));
            }
            start.countDown();
            List<JsonNode> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(15, TimeUnit.SECONDS);
                assertThat(response.getStatusCode().value())
                        .as("并发双击的两次 HTTP 都应 200：%s", response.getBody()).isEqualTo(200);
                responses.add(readJson(response.getBody()));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("正常中奖（积分档）：次数 −1、预扣返回即台账已动（issued +1）、明细一行、record+DRAW 明细各一行")
    void draw_normalWin_pointsPrize() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 2);

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertSuccess(response, "单抽");
        JsonNode data = response.path("data");
        assertThat(data.path("recordId").asText()).as("对外 recordId 是 UUID 字符串").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(data.path("prizeType").asText()).isEqualTo("VIRTUAL");
        assertThat(data.path("prizeId").asText()).isEqualTo(AWARD_POINTS);
        assertThat(data.path("tier").asInt()).isEqualTo(3);
        assertThat(data.path("name").asText()).isEqualTo("20 积分");
        assertThat(data.path("pointsAmount").asInt()).as("积分档面额取自台账").isEqualTo(20);

        assertThat(remainingChancesOf(USER)).as("次数 −1").isEqualTo(1);
        assertThat(virtualIssuedQtyOf(POINTS_INV_ID)).as("预扣返回即台账已动（issued +1）").isEqualTo(1L);
        assertThat(virtualDetailCount(POINTS_INV_ID)).as("明细一行").isEqualTo(1);
        assertThat(drawRecordCountOf(USER)).isEqualTo(1);
        String recordId = data.path("recordId").asText();
        assertThat(drawDetailCountOf(recordId)).as("DRAW 动作明细一行").isEqualTo(1);
        var record = jdbcTemplate.queryForMap(
                "SELECT state, inv_id FROM draw_record WHERE record_id = ?", recordId);
        assertThat(record.get("state")).isEqualTo("待兑换");
        assertThat((String) record.get("inv_id")).isEqualTo(POINTS_INV_ID);
    }

    @Test
    @DisplayName("抽到实物：预扣返回即台账已动（surplus −1、waiting +1），配额明细一行")
    void draw_normalWin_physicalPrize() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_BAG);
        giveChances(USER, 1);

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertSuccess(response, "单抽");
        assertThat(response.path("data").path("prizeType").asText()).isEqualTo("PHYSICAL");
        assertThat(quotaDetailCount(QUOTA_INV_ID)).as("配额明细一行").isEqualTo(1);
        var quota = jdbcTemplate.queryForMap(
                "SELECT surplus_qty, waiting_qty, issued_qty FROM physical_marketing_quota WHERE inv_id = ?",
                QUOTA_INV_ID);
        assertInt(quota.get("surplus_qty"), 9, "预扣返回即台账已动（surplus −1）");
        assertInt(quota.get("waiting_qty"), 1, "waiting 同事务已增");
        assertInt(quota.get("issued_qty"), 0, "未兑换");
        assertThat(drawRecordCountOf(USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("谢谢参与也走成功：state=已完成、无任何库存副作用")
    void draw_thanksPrize_noInventorySideEffect() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_THANKS);
        giveChances(USER, 1);

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertSuccess(response, "单抽");
        assertThat(response.path("data").path("prizeType").asText()).isEqualTo("THANKS");
        assertThat(remainingChancesOf(USER)).as("谢谢参与也扣次数").isZero();
        var record = jdbcTemplate.queryForMap(
                "SELECT state, inv_id FROM draw_record WHERE user_id = ?", USER);
        assertThat(record.get("state")).as("THANKS 落库即已完成").isEqualTo("已完成");
        assertThat(record.get("inv_id")).as("无库存来源").isNull();
        assertThat(virtualDetailCount(POINTS_INV_ID)).isZero();
        assertThat(quotaDetailCount(QUOTA_INV_ID)).isZero();
    }

    @Test
    @DisplayName("次数不足：NO_CHANCE，无任何副作用（无记录、无明细、Redis 不动）")
    void draw_noChance_noSideEffect() {
        prepareDefaultScenario();

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertFailure(response, "4303", "抽奖次数不足");
        assertThat(drawRecordCountOf(USER)).isZero();
        assertThat(virtualDetailCount(POINTS_INV_ID)).isZero();
        assertThat(quotaDetailCount(QUOTA_INV_ID)).isZero();
    }

    @Test
    @DisplayName("黑名单拦截：强制出 blacklist 奖品位，无库存预扣")
    void draw_blacklist_shortCircuits() {
        prepareDefaultScenario();
        String blacklistAward = "8899";
        insertAward(blacklistAward, "黑名单安慰", "VIRTUAL", "0.0000", 9, "0", "blacklist");
        raffleWarmupService.warmupByStrategyId(STRATEGY_ID);
        blacklistUser(STRATEGY_ID, USER);
        giveChances(USER, 1);

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertSuccess(response, "单抽");
        assertThat(response.path("data").path("prizeId").asText()).isEqualTo(blacklistAward);
        assertThat(virtualDetailCount(POINTS_INV_ID)).as("黑名单无库存预扣").isZero();
    }

    @Test
    @DisplayName("配额不足走兜底：改扣兜底虚拟权益、配额段零写入（无 waiting_qty）")
    void draw_quotaExhausted_fallsBack() {
        prepareDefaultScenario();
        // 实物配额余量清零（surplus 0），概率逼它落到实物档
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = 0 WHERE inv_id = ?", QUOTA_INV_ID);
        onlyAwardRate(AWARD_BAG);
        giveChances(USER, 1);

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertSuccess(response, "单抽");
        JsonNode data = response.path("data");
        assertThat(data.path("prizeId").asText()).as("落兜底奖品位").isEqualTo(AWARD_FALLBACK);
        assertThat(data.path("name").asText()).isEqualTo("5 积分安慰奖");
        assertThat(data.path("pointsAmount").asInt()).isEqualTo(5);
        assertThat(quotaDetailCount(QUOTA_INV_ID)).as("配额段零写入").isZero();
        assertThat(virtualIssuedQtyOf(FALLBACK_INV_ID)).as("兜底虚拟权益已扣（台账 issued +1）").isEqualTo(1L);
        assertThat(virtualDetailCount(FALLBACK_INV_ID)).isEqualTo(1);
        assertThat(drawRecordCountOf(USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("虚拟档权益不足也走兜底：积分池扣空 → 落兜底奖品位、积分段零写入")
    void draw_virtualExhausted_fallsBack() {
        prepareDefaultScenario();
        // 积分池余量扣空（surplus 0），概率逼它落到积分档
        jdbcTemplate.update("UPDATE virtual_inventory_quota SET surplus_qty = 0 WHERE inv_id = ?", POINTS_INV_ID);
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 1);

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertSuccess(response, "单抽");
        JsonNode data = response.path("data");
        assertThat(data.path("prizeId").asText()).as("落兜底奖品位").isEqualTo(AWARD_FALLBACK);
        assertThat(data.path("pointsAmount").asInt()).isEqualTo(5);
        assertThat(virtualDetailCount(POINTS_INV_ID)).as("积分池段零写入").isZero();
        assertThat(virtualIssuedQtyOf(POINTS_INV_ID)).isZero();
        assertThat(virtualIssuedQtyOf(FALLBACK_INV_ID)).as("兜底虚拟权益已扣（台账 issued +1）").isEqualTo(1L);
        assertThat(virtualDetailCount(FALLBACK_INV_ID)).isEqualTo(1);
        assertThat(drawRecordCountOf(USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：同 token 重放返回同一 recordId，不重复扣次数、不重复预扣")
    void draw_sameTokenReplay_isIdempotent() {
        prepareDefaultScenario();
        // 逼到积分档：库存断言才与「哪一档中奖」无关（默认概率下有 0.5 概率落谢谢参与，那档不碰库存桶）
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 3);

        JsonNode first = draw(USER, TOKEN);
        JsonNode replay = draw(USER, TOKEN);

        assertThat(replay.path("data").path("recordId").asText())
                .as("同一 token 同一记录")
                .isEqualTo(first.path("data").path("recordId").asText());
        assertThat(remainingChancesOf(USER)).as("次数只扣一次").isEqualTo(2);
        assertThat(virtualIssuedQtyOf(POINTS_INV_ID)).as("净扣一份").isEqualTo(1L);
        assertThat(virtualDetailCount(POINTS_INV_ID)).as("明细只有一行").isEqualTo(1);
        assertThat(drawRecordCountOf(USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("同 token 并发双击（虚拟段）：库存明细一行、台账净增一份、draw_record 一行")
    void draw_concurrentSameToken_virtualSegment_deductsOnce() throws Exception {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 1);
        preConsumeDrawCount(USER, TOKEN);

        List<JsonNode> responses = drawConcurrently(USER, TOKEN);

        String recordId = responses.get(0).path("data").path("recordId").asText();
        for (JsonNode response : responses) {
            TestingEnvironment.assertSuccess(response, "并发双击");
            assertThat(response.path("data").path("recordId").asText()).as("同一 token 回放同一记录")
                    .isEqualTo(recordId);
        }
        assertThat(virtualDetailCount(POINTS_INV_ID)).as("业务唯一键保证明细只有一行").isEqualTo(1);
        assertThat(virtualIssuedQtyOf(POINTS_INV_ID)).as("第二笔撞键按成功回放，净扣一份").isEqualTo(1L);
        assertThat(drawRecordCountOf(USER)).as("draw_record 一行").isEqualTo(1);
        var detail = jdbcTemplate.queryForMap(
                "SELECT biz_type, biz_id FROM virtual_inventory_quota_detail WHERE inv_id = ?", POINTS_INV_ID);
        assertThat(detail.get("biz_type")).isEqualTo("LOTTERY_DRAW");
        assertThat(((Number) detail.get("biz_id")).longValue()).isEqualTo(recordId);
    }

    @Test
    @DisplayName("同 token 并发双击（实物段）：库存明细一行、台账净增一份、draw_record 一行")
    void draw_concurrentSameToken_physicalSegment_deductsOnce() throws Exception {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_BAG);
        giveChances(USER, 1);
        preConsumeDrawCount(USER, TOKEN);

        List<JsonNode> responses = drawConcurrently(USER, TOKEN);

        String recordId = responses.get(0).path("data").path("recordId").asText();
        for (JsonNode response : responses) {
            TestingEnvironment.assertSuccess(response, "并发双击");
            assertThat(response.path("data").path("recordId").asText()).as("同一 token 回放同一记录")
                    .isEqualTo(recordId);
        }
        assertThat(quotaDetailCount(QUOTA_INV_ID)).as("业务唯一键保证明细只有一行").isEqualTo(1);
        var quotaLedger = jdbcTemplate.queryForMap(
                "SELECT surplus_qty, waiting_qty FROM physical_marketing_quota WHERE inv_id = ?", QUOTA_INV_ID);
        assertInt(quotaLedger.get("waiting_qty"), 1, "第二笔撞键按成功回放，净扣一份");
        assertInt(quotaLedger.get("surplus_qty"), 9, "预扣返回即台账已动");
        assertThat(drawRecordCountOf(USER)).as("draw_record 一行").isEqualTo(1);
        var detail = jdbcTemplate.queryForMap(
                "SELECT type, biz_type, biz_id FROM physical_marketing_quota_detail WHERE inv_id = ?", QUOTA_INV_ID);
        assertThat(((Number) detail.get("type")).intValue()).as("预扣结转 type=1").isEqualTo(1);
        assertThat(detail.get("biz_type")).isEqualTo("LOTTERY_DRAW");
        assertThat(((Number) detail.get("biz_id")).longValue()).isEqualTo(recordId);
    }

    @Test
    @DisplayName("不同 token 是两次抽奖：次数、库存、记录各自累加")
    void draw_differentToken_isNewDraw() {
        prepareDefaultScenario();
        giveChances(USER, 2);

        JsonNode first = draw(USER, TOKEN);
        JsonNode second = draw(USER, TOKEN + "-2");

        assertThat(second.path("data").path("recordId").asText())
                .isNotEqualTo(first.path("data").path("recordId").asText());
        assertThat(remainingChancesOf(USER)).isZero();
        assertThat(drawRecordCountOf(USER)).isEqualTo(2);
    }

    @Test
    @DisplayName("预热降级：Redis 概率表清空后仍能出奖（DB 直算），记录照常落库")
    void draw_coldWarmup_degradesToDatabase() {
        prepareDefaultScenario();
        giveChances(USER, 1);
        // 只清决策侧（概率表 + 预热态），库存桶保留——模拟「预热丢了但库存健在」
        redissonClient.getKeys().deleteByPattern("bs:raffle:*");

        JsonNode response = draw(USER, TOKEN);

        TestingEnvironment.assertSuccess(response, "单抽");
        assertThat(response.path("data").path("recordId").asText()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(drawRecordCountOf(USER)).isEqualTo(1);
        assertThat(drawDetailCountOf(response.path("data").path("recordId").asText())).isEqualTo(1);
    }

    @Test
    @DisplayName("活动未开始 / 已结束 / 已关闭：三种都拒且无副作用")
    void draw_activityGates_reject() {
        prepareDefaultScenario();
        giveChances(USER, 1);

        // 窗口必须相对「现在」算：夹具窗口（2026-01-01 起）是绝对值，拿它加天数在当前日期下可能仍在过去
        insertActivity(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2), "open");
        TestingEnvironment.assertFailure(draw(USER, TOKEN), "4301", "活动还没开始");

        insertActivity(LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), "open");
        TestingEnvironment.assertFailure(draw(USER, TOKEN), "4302", "活动已结束");

        insertActivity(ACTIVITY_START, ACTIVITY_END, "close");
        TestingEnvironment.assertFailure(draw(USER, TOKEN), "4302", "活动已结束");

        assertThat(drawRecordCountOf(USER)).as("被拒的抽奖不落记录").isZero();
        assertThat(remainingChancesOf(USER)).as("被拒的抽奖不扣次数").isEqualTo(1);
    }
}
