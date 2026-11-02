package com.betterschool.marketing.integration.points;

import com.betterschool.marketing.integration.support.PointsApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中奖入账链路（桶模型验收：入账落桶）：收到一条 {@code bs-lottery-drawn} 的积分档 →
 * 桶入账 / 余额增加 / 流水出现 / {@code bs-points-changed} 发出；重投不重复入账。
 */
@DisplayName("积分 · 抽奖中奖入账")
class PointsLotteryWinIntegrationTest extends PointsApiTestBase {

    private static final String USER_ID = "user-1001";
    private static final String RECORD_ID = "70001";

    @Test
    @DisplayName("积分档中奖：桶入账 + 余额增加 + 流水出现 + 积分变动事件发出")
    void lotteryWin_creditsPointsBucketAndEmitsChangedEvent() {
        publishLotteryDrawn(USER_ID, RECORD_ID, "VIRTUAL", 200);

        // 桶：一笔入账一行，带自己的过期时间（点数 365 天）与业务 bucket_id
        assertThat(bucketCountOf(USER_ID)).as("入账一行桶").isEqualTo(1);
        var bucket = jdbcTemplate.queryForMap(
                "SELECT bucket_id, amount, remain, reason_type, ref_id, expire_at FROM points_bucket WHERE user_id = ?",
                USER_ID);
        assertThat((String) bucket.get("bucket_id"))
                .as("bucket_id 是 UUID 字符串业务 ID，不复用物理自增 id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(bucket.get("amount")).isEqualTo(200);
        assertThat(bucket.get("remain")).isEqualTo(200);
        assertThat(bucket.get("reason_type")).isEqualTo("LOTTERY_WIN");
        assertThat(String.valueOf(bucket.get("ref_id"))).isEqualTo(String.valueOf(RECORD_ID));
        assertThat((LocalDateTime) bucket.get("expire_at"))
                .as("过期时间 = 入账时点 + points_valid_days(365)")
                .isAfter(LocalDateTime.now().plusDays(364));

        // 余额接口
        JsonNode balance = getJson("/api/points/balance", USER_ID);
        assertSuccess(balance, "查余额");
        assertThat(balance.path("data").path("balance").asInt()).isEqualTo(200);

        // 流水接口：一条 IN
        JsonNode transactions = getJson("/api/points/transactions?page=1&pageSize=10", USER_ID);
        assertSuccess(transactions, "查流水");
        JsonNode page = transactions.path("data");
        assertThat(page.path("total").asLong()).isEqualTo(1);
        JsonNode row = page.path("list").get(0);
        assertThat(row.path("direction").asText()).isEqualTo("IN");
        assertThat(row.path("amount").asInt()).isEqualTo(200);
        assertThat(row.path("reasonType").asText()).isEqualTo("LOTTERY_WIN");
        assertThat(row.path("refId").asText()).isEqualTo(RECORD_ID);
        assertThat(row.path("expireAt").isNull()).as("入账行带过期时间").isFalse();

        // 事件：{userId, delta, balanceAfter, reasonType, refId}
        JsonNode event = onlyPointsChangedEvent();
        assertThat(event.path("userId").asText()).isEqualTo(USER_ID);
        assertThat(event.path("delta").asInt()).isEqualTo(200);
        assertThat(event.path("balanceAfter").asInt()).isEqualTo(200);
        assertThat(event.path("reasonType").asText()).isEqualTo("LOTTERY_WIN");
        assertThat(event.path("refId").asText()).isEqualTo(RECORD_ID);
    }

    @Test
    @DisplayName("同一条中奖事件重投：不重复入账、不重复发事件")
    void lotteryWin_replayedPayload_isIdempotent() {
        publishLotteryDrawn(USER_ID, RECORD_ID, "VIRTUAL", 200);
        publishLotteryDrawn(USER_ID, RECORD_ID, "VIRTUAL", 200);

        assertThat(bucketCountOf(USER_ID)).as("uk(user_id, reason_type, ref_id) 兜住重投").isEqualTo(1);
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(200);
        assertThat(outboxPayloads("bs-points-changed")).as("重投不再发一次变动事件").hasSize(1);
    }

    @Test
    @DisplayName("非积分档（谢谢参与 / 实物）不入账也不发事件")
    void nonPointsPrize_isIgnored() {
        publishLotteryDrawn(USER_ID, RECORD_ID, "THANKS", null);
        publishLotteryDrawn(USER_ID, RECORD_ID + "-next", "PHYSICAL", null);

        assertThat(bucketCountOf(USER_ID)).isZero();
        assertThat(pointsAccountService.getBalance(USER_ID)).isZero();
        assertThat(outboxPayloads("bs-points-changed")).isEmpty();
    }

    @Test
    @DisplayName("未登录查余额 / 流水返回 401")
    void anonymous_isUnauthorized() {
        ResponseEntity<String> balance = get("/api/points/balance", null);
        ResponseEntity<String> transactions = get("/api/points/transactions", null);

        assertThat(balance.getStatusCode().value()).isEqualTo(401);
        assertThat(transactions.getStatusCode().value()).isEqualTo(401);
    }
}
