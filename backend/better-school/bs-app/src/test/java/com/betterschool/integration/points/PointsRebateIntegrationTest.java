package com.betterschool.integration.points;

import com.betterschool.integration.support.PointsApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行为返利入账（桶模型新增能力）：按 {@code action_rebate_config} 的 POINTS 规则发放，
 * 落一行 REBATE 桶并发变动事件；幂等键 = (userId, REBATE, refId)。
 */
@DisplayName("积分 · 行为返利入账")
class PointsRebateIntegrationTest extends PointsApiTestBase {

    private static final String USER_ID = "user-1004";
    private static final String REF_ID = "60001";
    private static final long POINTS_CONFIG_ID = 1L;

    @Test
    @DisplayName("签到返利（POINTS 规则开启）：落 REBATE 桶 + 变动事件")
    void addForRebate_withOpenRule_addsRebateBucket() {
        insertRebateRule("sign", "POINTS", 20, POINTS_CONFIG_ID, "open");

        boolean granted = pointsAccountService.addForRebate(USER_ID, REF_ID, 20);

        assertThat(granted).isTrue();
        var bucket = jdbcTemplate.queryForMap(
                "SELECT amount, remain, reason_type, ref_id, expire_at FROM points_bucket WHERE user_id = ?", USER_ID);
        assertThat(bucket.get("amount")).as("按规则值发放").isEqualTo(20);
        assertThat(bucket.get("remain")).isEqualTo(20);
        assertThat(bucket.get("reason_type")).isEqualTo("REBATE");
        assertThat(String.valueOf(bucket.get("ref_id"))).isEqualTo(String.valueOf(REF_ID));
        assertThat((LocalDateTime) bucket.get("expire_at"))
                .as("过期时间 = 入账时点 + points_valid_days(365)")
                .isAfter(LocalDateTime.now().plusDays(364));

        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(20);

        JsonNode event = onlyPointsChangedEvent();
        assertThat(event.path("userId").asText()).isEqualTo(USER_ID);
        assertThat(event.path("delta").asInt()).isEqualTo(20);
        assertThat(event.path("balanceAfter").asInt()).isEqualTo(20);
        assertThat(event.path("reasonType").asText()).isEqualTo("REBATE");
        assertThat(event.path("refId").asText()).isEqualTo(REF_ID);
    }

    @Test
    @DisplayName("同一发放幂等键重放：不重复入账、不重复发事件")
    void addForRebate_sameRefId_isIdempotent() {
        insertRebateRule("sign", "POINTS", 20, POINTS_CONFIG_ID, "open");

        pointsAccountService.addForRebate(USER_ID, REF_ID, 20);
        pointsAccountService.addForRebate(USER_ID, REF_ID, 20);

        assertThat(bucketCountOf(USER_ID)).as("uk(user_id, reason_type, ref_id) 兜住重放").isEqualTo(1);
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(20);
        assertThat(outboxPayloads("bs-points-changed")).hasSize(1);
    }

    @Test
    @DisplayName("规则未配置或已关闭：返回 false 且什么都不做")
    void addForRebate_withoutOpenRule_isNoOp() {
        assertThat(pointsAccountService.addForRebate(USER_ID, REF_ID, 0))
                .as("没有可发金额 = 不发放").isFalse();

        insertRebateRule("sign", "POINTS", 20, POINTS_CONFIG_ID, "close");
        assertThat(pointsAccountService.addForRebate(USER_ID, REF_ID, 0))
                .as("规则关闭 = 该行为暂不发放").isFalse();

        assertThat(bucketCountOf(USER_ID)).isZero();
        assertThat(pointsAccountService.getBalance(USER_ID)).isZero();
        assertThat(outboxPayloads("bs-points-changed")).isEmpty();
    }

    @Test
    @DisplayName("只认 POINTS 规则：次数规则（DRAW_COUNT）不给积分域用")
    void addForRebate_ignoresDrawCountRules() {
        insertRebateRule("sign", "DRAW_COUNT", 1, 2L, "open");

        assertThat(pointsAccountService.addForRebate(USER_ID, REF_ID, 0)).isFalse();
        assertThat(bucketCountOf(USER_ID)).isZero();
    }
}
