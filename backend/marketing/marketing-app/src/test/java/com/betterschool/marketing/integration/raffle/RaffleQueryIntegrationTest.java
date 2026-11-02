package com.betterschool.marketing.integration.raffle;

import com.betterschool.marketing.domain.points.service.IPointsAccountService;
import com.betterschool.marketing.integration.support.RaffleApiTestBase;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.common.IdempotencyKeys;
import com.betterschool.marketing.types.id.UuidIdGenerator;
import com.betterschool.marketing.types.points.PointsReasonType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽奖读侧 + 兑换次数集成用例（票 13 验收「读侧接口接新表」）：
 * 活动信息三态、奖品目录台账口径、我的次数、中奖记录、我的奖品四段状态，以及兑换的积分扣分 + 次数入账。
 */
public class RaffleQueryIntegrationTest extends RaffleApiTestBase {

    private static final String USER = "user-3101";
    private static final String OTHER = "user-3102";
    /** 兑换的幂等 token：写接口要求客户端提供，同一意图重试必须带同一个 */
    private static final String EXCHANGE_TOKEN = "e1a2b3c4-2222-4bbb-8ccc-000000000002";

    @Autowired
    private IPointsAccountService pointsAccountService;

    // ---- 活动信息 ----

    @Test
    @DisplayName("活动信息免登录可读，status 按 state 与窗口现算；无活动数据时 data 为 null")
    void activityInfo_anonymousReadable() {
        insertActivityOpen();
        insertStrategy(null);
        assertThat(activityInfo().path("data").path("status").asText()).isEqualTo("ONGOING");
        assertThat(activityInfo().path("data").path("name").asText()).isEqualTo("开学季抽奖");

        // 窗口相对「现在」算：夹具窗口起点是绝对值（2026-01-01），直接加天数在当前日期下仍是过去
        insertActivity(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2), "open");
        assertThat(activityInfo().path("data").path("status").asText()).isEqualTo("NOT_STARTED");

        insertActivity(LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), "open");
        assertThat(activityInfo().path("data").path("status").asText()).isEqualTo("ENDED");

        insertActivity(ACTIVITY_START, ACTIVITY_END, "close");
        assertThat(activityInfo().path("data").path("status").asText()).isEqualTo("ENDED");

        jdbcTemplate.update("DELETE FROM draw_activity");
        assertThat(activityInfo().path("data").isNull()).as("无活动返回 null data").isTrue();
    }

    @Test
    @DisplayName("奖品目录免登录可读：台账口径 remaining/total、不限量档 unlimited、积分档面额")
    void prizeCatalog_ledgerNumbers() {
        prepareDefaultScenario();

        JsonNode prizes = prizeCatalog().path("data");
        assertThat(prizes).hasSize(4);
        var byAward = new java.util.HashMap<String, JsonNode>();
        prizes.forEach(prize -> byAward.put(prize.path("prizeId").asText(), prize));

        assertThat(byAward.get(AWARD_POINTS).path("type").asText()).isEqualTo("VIRTUAL");
        assertThat(byAward.get(AWARD_POINTS).path("pointsAmount").asInt()).isEqualTo(20);
        assertThat(byAward.get(AWARD_POINTS).path("remaining").asInt()).isEqualTo(100);
        assertThat(byAward.get(AWARD_POINTS).path("total").asInt()).isEqualTo(100);
        assertThat(byAward.get(AWARD_POINTS).path("unlimited").asBoolean()).isFalse();

        assertThat(byAward.get(AWARD_BAG).path("type").asText()).isEqualTo("PHYSICAL");
        assertThat(byAward.get(AWARD_BAG).path("remaining").asInt()).isEqualTo(10);

        assertThat(byAward.get(AWARD_THANKS).path("unlimited").asBoolean()).as("THANKS 不限量").isTrue();
        assertThat(byAward.get(AWARD_THANKS).path("remaining").isNull()).isTrue();

        assertThat(byAward.get(AWARD_FALLBACK).path("remaining").asInt()).as("兜底也是库存档").isEqualTo(100);
    }

    @Test
    @DisplayName("我的抽奖次数：available = 未过期桶 SUM(remain)，todayDrawn 取当日 draw_record 行数")
    void drawChances_balanceAndToday() {
        prepareDefaultScenario();
        giveChances(USER, 3);
        insertDrawRecord(USER, LocalDateTime.now());

        JsonNode chances = drawChances(USER).path("data");
        assertThat(chances.path("available").asInt()).isEqualTo(3);
        assertThat(chances.path("todayDrawn").asInt()).isEqualTo(1);
    }

    // ---- 中奖记录 / 我的奖品 ----

    @Test
    @DisplayName("中奖记录：过滤谢谢参与、create_time DESC、只看得见自己")
    void records_filteredAndOwn() {
        prepareDefaultScenario();
        String winRecordId = insertDrawRecord(USER, LocalDateTime.now().minusHours(2), AWARD_POINTS, "VIRTUAL");
        insertDrawRecord(USER, LocalDateTime.now().minusHours(1), AWARD_THANKS, "THANKS");
        insertDrawRecord(OTHER, LocalDateTime.now(), AWARD_BAG, "PHYSICAL");

        JsonNode records = records(USER, 1, 10).path("data");
        assertThat(records.path("total").asInt()).as("谢谢参与被过滤").isEqualTo(1);
        JsonNode row = records.path("list").get(0);
        assertThat(row.path("recordId").asText()).isEqualTo(winRecordId);
        assertThat(row.path("prizeType").asText()).isEqualTo("VIRTUAL");
        assertThat(row.path("pointsAmount").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("我的奖品：状态过滤映射四段状态，claimedAt 取 EXCHANGE 动作时刻")
    void myPrizes_statusTabs() {
        prepareDefaultScenario();
        String pendingId = insertDrawRecord(USER, LocalDateTime.now().minusHours(1), AWARD_BAG, "PHYSICAL");
        insertDrawRecord(USER, LocalDateTime.now(), AWARD_POINTS, "VIRTUAL", "已完成", "COMPLETE");

        assertThat(myPrizes(USER, "PENDING").path("data").path("total").asInt()).isEqualTo(1);
        JsonNode pending = myPrizes(USER, "PENDING").path("data").path("list").get(0);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("claimedAt").isNull()).isTrue();

        // 待兑换推进到已兑换：state 翻转 + EXCHANGE 动作明细
        jdbcTemplate.update("UPDATE draw_record SET state = '已兑换' WHERE record_id = ?", pendingId);
        jdbcTemplate.update("""
                        INSERT INTO draw_record_detail (user_id, record_id, type, create_time, update_time)
                        VALUES (?, ?, 'EXCHANGE', NOW(), NOW())
                        """, USER, pendingId);

        JsonNode exchanged = myPrizes(USER, "EXCHANGED").path("data").path("list").get(0);
        assertThat(exchanged.path("status").asText()).isEqualTo("EXCHANGED");
        assertThat(exchanged.path("claimedAt")).as("claimedAt = EXCHANGE 时刻").isNotNull();

        assertThat(myPrizes(USER, null).path("data").path("total"))
                .as("不传 status 按第一段（待兑换）")
                .isEqualTo(myPrizes(USER, "PENDING").path("data").path("total"));

        TestingEnvironment.assertFailure(myPrizes(USER, "BOGUS"), "400", "参数不合法");
    }

    // ---- 兑换次数 ----

    @Test
    @DisplayName("兑换次数成功：积分扣 + 次数入账，返回兑换单 id 与兑后余额")
    void exchangeDrawCount_success() {
        prepareDefaultScenario();
        pointsAccountService.addForLotteryWin(USER, UuidIdGenerator.INSTANCE.nextId(), 500);

        JsonNode response = exchangeDrawCount(USER, EXCHANGE_TOKEN);

        TestingEnvironment.assertSuccess(response, "兑换次数");
        JsonNode data = response.path("data");
        assertThat(data.path("redeemOrderId").asText()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(data.path("drawCount").asInt()).isEqualTo(1);
        assertThat(remainingChancesOf(USER)).isEqualTo(1);
        Integer pointsBalance = pointsAccountService.getBalance(USER);
        assertThat(pointsBalance).as("积分扣 100").isEqualTo(400);
        // 两边同值同事务：次数桶 ref_id = points_detail.ref_id = 同一兑换单 id（uuid-biz-id 票 07 起为 UUID 字符串）
        String refId = jdbcTemplate.queryForObject(
                "SELECT ref_id FROM draw_count_bucket WHERE user_id = ?", String.class, USER);
        Integer pointsRef = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM points_detail WHERE user_id = ? AND ref_id = ? AND reason_type = 'REDEEM_DRAW_COUNT'",
                Integer.class, USER, refId);
        assertThat(pointsRef).as("同值口径：同一 ref_id 双侧各一条").isEqualTo(1);
    }

    @Test
    @DisplayName("兑换次数幂等：同 token 重放只入账一次，积分只扣一次、次数只加一次")
    void exchangeDrawCount_sameTokenReplay_isIdempotent() {
        prepareDefaultScenario();
        pointsAccountService.addForLotteryWin(USER, UuidIdGenerator.INSTANCE.nextId(), 500);

        JsonNode first = exchangeDrawCount(USER, EXCHANGE_TOKEN);
        JsonNode replay = exchangeDrawCount(USER, EXCHANGE_TOKEN);

        TestingEnvironment.assertSuccess(first, "兑换次数");
        TestingEnvironment.assertSuccess(replay, "兑换次数重放");
        assertThat(replay.path("data").path("redeemOrderId").asText())
                .as("同一 token 同一单号（确定性映射，不落重放表）")
                .isEqualTo(first.path("data").path("redeemOrderId").asText());
        assertThat(remainingChancesOf(USER)).as("次数只加一次").isEqualTo(1);
        assertThat(pointsAccountService.getBalance(USER)).as("积分只扣一次").isEqualTo(400);
        Integer bucketRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_bucket WHERE user_id = ?", Integer.class, USER);
        assertThat(bucketRows).as("入账桶只有一行").isEqualTo(1);

        // 换 token 才是新的一笔
        exchangeDrawCount(USER, EXCHANGE_TOKEN + "-2");
        assertThat(remainingChancesOf(USER)).as("新 token = 新兑换").isEqualTo(2);
        assertThat(pointsAccountService.getBalance(USER)).isEqualTo(300);
    }

    @Test
    @DisplayName("积分不足：LOTTERY_INSUFFICIENT_POINTS，一分不扣、一桶不加")
    void exchangeDrawCount_insufficientPoints() {
        prepareDefaultScenario();
        pointsAccountService.addForLotteryWin(USER, UuidIdGenerator.INSTANCE.nextId(), 50);

        TestingEnvironment.assertFailure(exchangeDrawCount(USER, EXCHANGE_TOKEN), "4306", "积分不足");

        assertThat(remainingChancesOf(USER)).isZero();
        assertThat(pointsAccountService.getBalance(USER)).isEqualTo(50);
    }

    @Test
    @DisplayName("兑换中间态可重试收敛：积分已扣而次数未入账时，带同一 token 重试只补入账、不重复扣分")
    void exchangeDrawCount_retryAfterDeductOnly_converges() {
        prepareDefaultScenario();
        pointsAccountService.addForLotteryWin(USER, UuidIdGenerator.INSTANCE.nextId(), 500);

        // 模拟「扣分已提交、入账还没发生」（进程崩溃 / 次数域短暂故障）：只走积分侧那半边，
        // ref_id 用与兑换同一个确定性单号——正是 case 层两步共用的幂等键
        String redeemOrderId = IdempotencyKeys.of(USER, EXCHANGE_TOKEN);
        var deducted = pointsAccountService.deduct(USER, 100, PointsReasonType.REDEEM_DRAW_COUNT, redeemOrderId);
        assertThat(deducted.isSuccess()).isTrue();
        assertThat(pointsAccountService.getBalance(USER)).isEqualTo(400);
        assertThat(remainingChancesOf(USER)).as("次数尚未入账").isZero();

        // 客户端带同一 token 重试：第一步幂等跳过、第二步补上
        JsonNode retried = exchangeDrawCount(USER, EXCHANGE_TOKEN);
        TestingEnvironment.assertSuccess(retried, "兑换次数重试");
        assertThat(retried.path("data").path("redeemOrderId").asText()).isEqualTo(redeemOrderId);
        assertThat(retried.path("data").path("drawCount").asInt()).isEqualTo(1);
        assertThat(pointsAccountService.getBalance(USER)).as("重试不重复扣分").isEqualTo(400);
        assertThat(remainingChancesOf(USER)).isEqualTo(1);
        Integer consumeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM points_detail WHERE user_id = ? AND ref_id = ? AND reason_type = 'REDEEM_DRAW_COUNT'",
                Integer.class, USER, redeemOrderId);
        assertThat(consumeRows).as("积分消费明细只有一条").isEqualTo(1);
    }

    @Test
    @DisplayName("同 token 重放复算 pointsAmount：重放路径读台账面额（幂等回放）")
    void replay_rebuildsPointsAmount() {
        prepareDefaultScenario();
        onlyAwardRateFor(AWARD_POINTS);
        giveChances(USER, 1);
        JsonNode first = draw(USER, "replay-points");
        JsonNode replay = draw(USER, "replay-points");
        assertThat(replay.path("data").path("pointsAmount").asInt())
                .isEqualTo(first.path("data").path("pointsAmount").asInt());
    }

    // ---- 夹具 ----

    /** 把概率变成确定性：全部 normal 档归零后只留目标档 1.0，重预热即必中该档 */
    private void onlyAwardRateFor(String awardId) {
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 0 WHERE type = 'normal'");
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 1 WHERE award_id = ?", awardId);
        raffleWarmupService.warmupByStrategyId(STRATEGY_ID);
    }

    private String insertDrawRecord(String userId, LocalDateTime createTime) {
        return insertDrawRecord(userId, createTime, AWARD_BAG, "PHYSICAL");
    }

    /** 直接塞一行 draw_record（快照列照新模型），返回 recordId；extraType 追加一条动作明细 */
    private String insertDrawRecord(String userId, LocalDateTime createTime, String awardId, String awardType) {
        return insertDrawRecord(userId, createTime, awardId, awardType, "待兑换", null);
    }

    private String insertDrawRecord(String userId, LocalDateTime createTime, String awardId, String awardType,
                                  String state, String extraType) {
        String recordId = UuidIdGenerator.INSTANCE.nextId();
        String invId = AWARD_POINTS.equals(awardId) ? POINTS_INV_ID : AWARD_BAG.equals(awardId) ? QUOTA_INV_ID : "0";
        jdbcTemplate.update("""
                        INSERT INTO draw_record (record_id, user_id, activity_id, strategy_id, award_id,
                                                 award_name, award_desc, award_type, level, inv_id, state,
                                                 create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, 'x', NULL, ?, 1, NULLIF(?, '0'), ?, ?, ?)
                        """,
                recordId, userId, ACTIVITY_ID, STRATEGY_ID, awardId, awardType, invId, state,
                java.sql.Timestamp.valueOf(createTime), java.sql.Timestamp.valueOf(createTime));
        jdbcTemplate.update("""
                        INSERT INTO draw_record_detail (user_id, record_id, type, create_time, update_time)
                        VALUES (?, ?, 'DRAW', NOW(), NOW())
                        """, userId, recordId);
        if (extraType != null) {
            jdbcTemplate.update("""
                            INSERT INTO draw_record_detail (user_id, record_id, type, create_time, update_time)
                            VALUES (?, ?, ?, NOW(), NOW())
                            """, userId, recordId, extraType);
        }
        return recordId;
    }
}
