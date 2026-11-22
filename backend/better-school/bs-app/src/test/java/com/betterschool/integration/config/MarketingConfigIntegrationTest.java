package com.betterschool.integration.config;

import com.betterschool.integration.support.PointsApiTestBase;
import com.betterschool.integration.support.TestingEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 营销配置后台（桶模型配套）：有效期配置 / 次数配置 / 行为返利配置的读写。
 * 读需登录、写需 ADMIN（照 lottery openBucket 的控制器内 role 判断）；
 * 改过的单行配置在用例后恢复，避免影响其他用例的 expire_at 断言。
 */
@DisplayName("营销配置 · 后台读写")
class MarketingConfigIntegrationTest extends PointsApiTestBase {

    private static final String USER_ID = "user-1006";
    private static final String ADMIN_ID = "admin-1";

    private String pointsValidityBackup;
    private String drawCountValidityBackup;

    @BeforeEach
    void backupValidity() {
        pointsValidityBackup = getJson("/api/marketing/config/points-validity", USER_ID)
                .path("data").path("pointsValidDays").asText();
        drawCountValidityBackup = getJson("/api/marketing/config/draw-count-validity", USER_ID)
                .path("data").path("drawCountValidDays").asText();
    }

    @AfterEach
    void restoreValidity() {
        put("/api/marketing/config/points-validity?pointsValidDays=" + pointsValidityBackup, adminToken(), null);
        put("/api/marketing/config/draw-count-validity?drawCountValidDays=" + drawCountValidityBackup, adminToken(), null);
    }

    @Test
    @DisplayName("读有效期配置：登录用户可读，默认种子 365 / 30")
    void getValidity_requiresLoginAndReturnsSeed() {
        JsonNode points = getJson("/api/marketing/config/points-validity", USER_ID);
        assertSuccess(points, "读积分有效期");
        assertThat(points.path("data").path("pointsValidDays").asInt()).isEqualTo(365);

        JsonNode drawCount = getJson("/api/marketing/config/draw-count-validity", USER_ID);
        assertSuccess(drawCount, "读次数有效期");
        assertThat(drawCount.path("data").path("drawCountValidDays").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("写有效期配置：非 ADMIN 被 403 拒，ADMIN 可改且 GET 可见")
    void updateValidity_adminOnly() {
        JsonNode forbidden = readJson(put(
                "/api/marketing/config/points-validity?pointsValidDays=200", tokenOf(USER_ID), null).getBody());
        assertFailure(forbidden, "403", "无权限，仅管理员可改营销配置");

        JsonNode updated = readJson(put(
                "/api/marketing/config/points-validity?pointsValidDays=200", adminToken(), null).getBody());
        assertSuccess(updated, "改积分有效期");
        assertThat(updated.path("data").path("pointsValidDays").asInt()).isEqualTo(200);

        JsonNode updatedCount = readJson(put(
                "/api/marketing/config/draw-count-validity?drawCountValidDays=60", adminToken(), null).getBody());
        assertSuccess(updatedCount, "改次数有效期");
        assertThat(updatedCount.path("data").path("drawCountValidDays").asInt()).isEqualTo(60);

        assertThat(getJson("/api/marketing/config/points-validity", USER_ID).path("data").path("pointsValidDays")
                .asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("有效期天数必须为正：0 返回参数错误且不改值")
    void updateValidity_rejectsNonPositiveDays() {
        JsonNode response = readJson(put(
                "/api/marketing/config/points-validity?pointsValidDays=0", adminToken(), null).getBody());
        assertFailure(response, "400", "有效期天数必须为正");
        assertThat(getJson("/api/marketing/config/points-validity", USER_ID).path("data").path("pointsValidDays")
                .asInt()).as("失败不改值").isEqualTo(365);
    }

    @Test
    @DisplayName("返利规则 upsert：同 (actionType, rebateType) 重提走更新，非法 rebateType 拒绝")
    void rebateUpsert_andValidation() {
        JsonNode created = readJson(postRebate("{\"actionType\":\"pay\",\"rebateType\":\"POINTS\","
                + "\"rebateName\":\"支付返积分\",\"rebateValue\":10,\"configId\":1,\"state\":\"open\"}"));
        assertSuccess(created, "新建返利规则");
        assertThat(created.path("data").path("actionType").asText()).isEqualTo("pay");
        assertThat(created.path("data").path("rebateValue").asInt()).isEqualTo(10);

        JsonNode updated = readJson(postRebate("{\"actionType\":\"pay\",\"rebateType\":\"POINTS\","
                + "\"rebateName\":\"支付返积分\",\"rebateValue\":30,\"configId\":1,\"state\":\"close\"}"));
        assertSuccess(updated, "更新返利规则");
        assertThat(updated.path("data").path("rebateValue").asInt()).isEqualTo(30);
        assertThat(updated.path("data").path("state").asText()).isEqualTo("close");

        // 列表里同键只有一行
        JsonNode list = getJson("/api/marketing/config/rebates", USER_ID);
        assertSuccess(list, "读返利规则列表");
        assertThat(list.path("data")).hasSize(1);

        JsonNode badType = readJson(postRebate("{\"actionType\":\"pay\",\"rebateType\":\"COUPON\","
                + "\"rebateName\":\"x\",\"rebateValue\":1,\"configId\":1,\"state\":\"open\"}"));
        assertFailure(badType, "400", "不支持的返利类型");
    }

    @Test
    @DisplayName("写返利规则非 ADMIN 被 403 拒")
    void rebateUpsert_adminOnly() {
        ResponseEntity<String> response = TestingEnvironment.post(restTemplate,
                "/api/marketing/config/rebates", tokenOf(USER_ID),
                "{\"actionType\":\"sign\",\"rebateType\":\"POINTS\",\"rebateName\":\"签到返积分\","
                        + "\"rebateValue\":5,\"configId\":1,\"state\":\"open\"}");
        JsonNode body = readJson(response.getBody());
        assertFailure(body, "403", "无权限，仅管理员可改营销配置");
        assertThat(getJson("/api/marketing/config/rebates", USER_ID).path("data")).isEmpty();
    }

    // ---------- HTTP 助手 ----------

    private String adminToken() {
        return tokenOf(ADMIN_ID, "ADMIN");
    }

    private ResponseEntity<String> put(String url, String bearerToken, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(jsonBody, headers), String.class);
    }

    private String postRebate(String jsonBody) {
        return TestingEnvironment.post(restTemplate, "/api/marketing/config/rebates", adminToken(), jsonBody)
                .getBody();
    }

    private JsonNode readJson(String body) {
        return TestingEnvironment.readJson(objectMapper, body);
    }
}
