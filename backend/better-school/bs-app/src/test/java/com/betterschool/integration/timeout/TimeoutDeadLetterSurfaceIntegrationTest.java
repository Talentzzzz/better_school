package com.betterschool.integration.timeout;

import com.betterschool.infrastructure.sharding.TimeoutTaskShardSlots;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.betterschool.integration.support.CommunityApiTestBase.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 死信管理面集成测试（内嵌化票 06）：落点 = 单体 8080 的 {@code /api/admin/timeout-tasks/**}。
 * <p>
 * 覆盖：鉴权（未登录 401 / USER 与 AUDITOR 都 403 / ADMIN 放行）、列表跨槽广播归并
 * （两行摆进不同物理分表都能翻出来 + updated_at 倒序）、分页钳制（页宽 50 / 页码 1000，
 * 越界夹住不报错）、重发与取消只打 FAILED 行（条件 UPDATE：非 FAILED 无副作用进失败清单/false）。
 * <p>
 * 死信前置态用裸 MySQL 直摆（{@code physicalUpdate}，夹具口径）：走真实「失败 16 次」路径
 * 要等退避窗口，扫描路径的死信判定已由票 04 的用例覆盖，这里只验管理面本身。
 */
class TimeoutDeadLetterSurfaceIntegrationTest extends TimeoutScanTestBase {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String LIST_URL = "/api/admin/timeout-tasks/dead-letters";
    private static final String RESEND_URL = "/api/admin/timeout-tasks/dead-letters/resend";
    private static final String CANCEL_URL = "/api/admin/timeout-tasks/dead-letters/cancel";

    private Session admin;

    @BeforeEach
    void setUpAdmin() {
        admin = adminSession();
    }

    // ---------- 鉴权 ----------

    @Test
    void 未登录访问管理面被过滤链401挡下() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, LIST_URL, null, null);
        assertThat(response.getStatusCode().value()).as("未登录 → 401").isEqualTo(401);
    }

    @Test
    void 普通用户与审核员都被403_仅管理员放行() {
        Session user = registerAndLogin("13800002001", "普通用户");
        assertThat(getJson(LIST_URL + "?page=1&pageSize=10", user).path("code").asText())
                .as("USER 应 403（登录态由过滤链放行、角色在控制器收口）")
                .isEqualTo("403");

        Session auditor = promotedSession("13800002002", "审核员", "AUDITOR");
        assertThat(getJson(LIST_URL + "?page=1&pageSize=10", auditor).path("code").asText())
                .as("AUDITOR 也不放行：角色收口在 ADMIN")
                .isEqualTo("403");

        assertThat(getJson(LIST_URL + "?page=1&pageSize=10", admin).path("code").asText())
                .as("ADMIN 放行")
                .isEqualTo("200");
    }

    // ---------- 列表：广播归并 + 排序 + 钳制 ----------

    @Test
    void 列表跨槽广播归并_两行落不同分表都翻得出来且按进死信时间倒序() {
        String early = bizIdForSlot("dl-early-", 3);
        String late = bizIdForSlot("dl-late-", 11);
        deadLetterRow(early, "2026-01-01 10:00:00");
        deadLetterRow(late, "2026-01-02 10:00:00");
        assertThat(slotOf(early)).as("前置：两行确实落在不同物理分表").isNotEqualTo(slotOf(late));

        JsonNode data = getJson(LIST_URL + "?page=1&pageSize=10", admin).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(2);
        List<String> bizIds = bizIdsOf(data.path("list"));
        assertThat(bizIds).as("updated_at 倒序：最近进死信的在前（跨表归并后排序仍精确）")
                .containsExactly(late, early);

        JsonNode firstRow = data.path("list").get(0);
        assertThat(firstRow.path("taskType").asText()).isEqualTo(TASK_TYPE);
        assertThat(firstRow.has("id")).as("不暴露物理主键").isFalse();
        assertThat(firstRow.path("retryCount").asInt()).isEqualTo(16);
    }

    @Test
    void 分页越界夹住不报错_页宽50页码1000() {
        deadLetterRow(bizIdForSlot("dl-clamp-", 5), "2026-01-01 10:00:00");

        JsonNode clampedPageSize = getJson(LIST_URL + "?page=1&pageSize=500", admin).path("data");
        assertThat(clampedPageSize.path("pageSize").asLong()).as("页宽夹到 50").isEqualTo(50);

        JsonNode clampedPage = getJson(LIST_URL + "?page=5000&pageSize=10", admin).path("data");
        assertThat(clampedPage.path("page").asLong()).as("页码夹到 1000").isEqualTo(1000);
        assertThat(clampedPage.path("list").isEmpty()).as("越界页夹住后正常返回空页而非报错").isTrue();

        JsonNode normalized = getJson(LIST_URL + "?page=0&pageSize=0", admin).path("data");
        assertThat(normalized.path("page").asLong()).as("下界归一").isEqualTo(1);
        assertThat(normalized.path("pageSize").asLong()).isEqualTo(1);
    }

    // ---------- 重发：条件 UPDATE 只打 FAILED ----------

    @Test
    void 批量重发只打FAILED行_在途行进失败清单_重开行落RETRYING清零计数() {
        String dead = bizIdForSlot("dl-resend-dead-", 7);
        String alive = bizIdForSlot("dl-resend-alive-", 7);
        deadLetterRow(dead, "2026-01-01 10:00:00");
        retryingRow(alive);
        LocalDateTime actionTimeBefore = dateTime(physicalRow(dead).get("action_time"));

        JsonNode response = postJson(RESEND_URL, Map.of("keys", List.of(
                Map.of("taskType", TASK_TYPE, "bizId", dead),
                Map.of("taskType", TASK_TYPE, "bizId", alive))), admin);
        assertSuccess(response, "批量重发");
        JsonNode result = response.path("data");
        assertThat(bizIdsOf(result.path("successList"))).as("FAILED 行被重开").containsExactly(dead);
        assertThat(bizIdsOf(result.path("failList"))).as("RETRYING 行不在死信，无副作用进失败清单")
                .containsExactly(alive);

        Map<String, Object> row = physicalRow(dead);
        assertThat(row.get("status")).as("FAILED → RETRYING").isEqualTo("RETRYING");
        assertThat(row.get("retry_count")).as("retry_count 清零").isEqualTo(0);
        assertThat(dateTime(row.get("action_time"))).as("action_time 不动（理论到期时刻不可变）")
                .isEqualTo(actionTimeBefore);
        LocalDateTime nextFireTime = dateTime(row.get("next_fire_time"));
        assertThat(nextFireTime).as("next_fire_time = now（下一扫描周期即重投）")
                .isAfter(LocalDateTime.now().minusMinutes(1))
                .isBefore(LocalDateTime.now().plusMinutes(1));

        assertThat(physicalRow(alive).get("status")).as("在途行原样").isEqualTo("RETRYING");
        assertThat(physicalRow(alive).get("retry_count")).isEqualTo(3);
    }

    @Test
    void 单条重发键写错或已被人处理时无副作用() {
        String dead = bizIdForSlot("dl-resend-miss-", 2);
        deadLetterRow(dead, "2026-01-01 10:00:00");

        JsonNode first = postJson(RESEND_URL, Map.of("keys", List.of(
                Map.of("taskType", TASK_TYPE, "bizId", dead))), admin);
        assertThat(bizIdsOf(first.path("data").path("successList"))).containsExactly(dead);

        JsonNode second = postJson(RESEND_URL, Map.of("keys", List.of(
                Map.of("taskType", TASK_TYPE, "bizId", dead))), admin);
        assertThat(bizIdsOf(second.path("data").path("failList"))).as("已重开成 RETRYING，第二次无副作用").containsExactly(dead);

        JsonNode missing = postJson(RESEND_URL, Map.of("keys", List.of(
                Map.of("taskType", TASK_TYPE, "bizId", "no-such-key"))), admin);
        assertThat(bizIdsOf(missing.path("data").path("failList"))).containsExactly("no-such-key");

        JsonNode badRequest = postJson(RESEND_URL, Map.of("keys", List.of()), admin);
        assertThat(badRequest.path("code").asText()).as("空 keys 拒绝").isEqualTo("400");
    }

    // ---------- 取消：FAILED → CANCELED 终态 ----------

    @Test
    void 取消死信置CANCELED_重复取消与取消非死信都返回false() {
        String dead = bizIdForSlot("dl-cancel-", 9);
        deadLetterRow(dead, "2026-01-01 10:00:00");

        JsonNode first = postJson(CANCEL_URL, Map.of("taskType", TASK_TYPE, "bizId", dead), admin);
        assertThat(first.path("data").asBoolean()).as("FAILED 行被取消").isTrue();
        assertThat(physicalRow(dead).get("status")).isEqualTo("CANCELED");

        assertThat(postJson(CANCEL_URL, Map.of("taskType", TASK_TYPE, "bizId", dead), admin)
                .path("data").asBoolean()).as("已是 CANCELED，再取消无副作用").isFalse();
        assertThat(postJson(CANCEL_URL, Map.of("taskType", TASK_TYPE, "bizId", "no-such-key"), admin)
                .path("data").asBoolean()).as("不存在的键无副作用").isFalse();
    }

    // ---------- 夹具 ----------

    /** 摆一行死信（FAILED、retry_count=16）并按给定时刻定 updated_at（翻页排序的判据） */
    private void deadLetterRow(String bizId, String updatedAt) {
        registerDue(bizId, -10);
        int updated = physicalUpdate(
                "UPDATE " + TimeoutTaskShardSlots.tableNameOf(slotOf(bizId))
                        + " SET status = 'FAILED', retry_count = 16, updated_at = ?"
                        + " WHERE task_type = ? AND biz_id = ?",
                LocalDateTime.parse(updatedAt, TS), TASK_TYPE, bizId);
        assertThat(updated).as("摆死信前置态失败：%s", bizId).isEqualTo(1);
    }

    /** 摆一行在途重试（RETRYING、retry_count=3）：验证「重发/取消不碰非 FAILED 行」 */
    private void retryingRow(String bizId) {
        registerDue(bizId, -10);
        int updated = physicalUpdate(
                "UPDATE " + TimeoutTaskShardSlots.tableNameOf(slotOf(bizId))
                        + " SET status = 'RETRYING', retry_count = 3"
                        + " WHERE task_type = ? AND biz_id = ?",
                TASK_TYPE, bizId);
        assertThat(updated).as("摆在途前置态失败：%s", bizId).isEqualTo(1);
    }

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        return promotedSession("13900002101", "管理员", "ADMIN");
    }

    /** 提权登录态：role 是签发快照，改库后必须重登（ AUDITOR 用例同款，见 ReviewDispatchSignalIntegrationTest） */
    private Session promotedSession(String phone, String nickname, String role) {
        Session registered = registerAndLogin(phone, nickname);
        jdbcTemplate.update("UPDATE bs_user SET role = ? WHERE user_id = ?", role, registered.userId());
        postJson("/api/user/send-code", Map.of("phone", phone), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + phone).get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", phone, "code", code), null);
        assertSuccess(loginResponse, role + " 重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    // ---------- 断言工具 ----------

    /** 双清单/列表行里的 bizId 序（data.list 的行只有自然键可认） */
    private static List<String> bizIdsOf(JsonNode arrayNode) {
        return java.util.stream.StreamSupport.stream(arrayNode.spliterator(), false)
                .map(node -> node.path("bizId").asText())
                .toList();
    }
}
