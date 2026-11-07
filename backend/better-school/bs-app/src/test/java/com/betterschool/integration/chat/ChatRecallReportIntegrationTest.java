package com.betterschool.integration.chat;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chat 撤回与举报集成测试（落地票 05）：单一 seam = REST 端点（recall / reports）+ 外部可观察状态（message/room/outbox/content_report/credit/notification）。
 * 覆盖：撤回窗口与本人校验、撤回三件套与预览替换、重复撤回幂等、消息举报入单幂等、AUDITOR 强制撤回无窗口、不走扣分流水、双方通知
 */
public class ChatRecallReportIntegrationTest extends CommunityApiTestBase {

    @Test
    void recallBySenderWithinWindowMarksShellAndPushesRecallEvent() {
        String roomId = createDirectRoomViaApi();
        String msg1 = sendText(roomId, "cmid-1", "第一条，等会儿撤回", sessionA);
        String msg2 = sendText(roomId, "cmid-2", "第二条，最新的", sessionA);

        // 撤回非最后一条：成员行列表预览不受影响
        postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), sessionA);
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND last_msg_preview = '第二条，最新的'")).isEqualTo(2);

        // 幂等：重复撤回静默成功，不重复写 RECALL 事件
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), sessionA), "重复撤回");
        assertThat(queryLong("SELECT COUNT(1) FROM outbox_message WHERE destination = '" + Constants.CHAT_RECALL_TOPIC + "'")).isEqualTo(1);

        // 撤回最后一条：预览替换 [已撤回]，占 seq 不回退、不动水位线（计入未读）
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/messages/2/recall", Map.of(), sessionA), "撤回最新消息");
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND last_msg_preview = '[已撤回]'")).as("双方用户行预览替换").isEqualTo(2);
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).as("撤回占 seq 不回退").isEqualTo(2);
        assertThat(queryLong("SELECT read_seq FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND user_id = '" + sessionB.userId() + "'" )).as("不动已读水位线").isEqualTo(0);

        // 三件套：status/recalled_at/recalled_by=USER；content 库内保留取证
        assertThat(queryString("SELECT state FROM message WHERE msg_id = '" + msg2 + "'")).isEqualTo("RECALLED");
        assertThat(queryString("SELECT recalled_by FROM message WHERE msg_id = '" + msg2 + "'")).isEqualTo("USER");
        assertThat(queryString("SELECT content FROM message WHERE msg_id = '" + msg2 + "'")).as("库内保留取证").isEqualTo("第二条，最新的");

        // 读路径脱敏空壳：content/ref 不出服务端
        JsonNode history = getJson("/api/chat/rooms/" + roomId + "/messages", sessionB);
        assertSuccess(history, "历史消息");
        JsonNode recalled = findMessageBySeq(history, 2);
        assertThat(recalled.path("status").asText()).isEqualTo("RECALLED");
        assertThat(recalled.path("content").isNull()).as("撤回后 content 不出服务端").isTrue();

        // RECALL 轻事件载荷只带定位（msg1 + msg2 两次撤回各一条）
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = '" + Constants.CHAT_RECALL_TOPIC + "'", String.class);
        assertThat(payloads).hasSize(2)
                .anySatisfy(payload -> assertThat(payload).contains("\"roomId\":\"" + roomId + "\"").contains("\"seq\":2"));
    }

    @Test
    void recallRejectsNonSenderExpiredWindowAndUnknownSeq() {
        String roomId = createDirectRoomViaApi();
        sendText(roomId, "cmid-1", "只能我撤", sessionA);

        // 非本人（会话内对方 / 会话外路人）一律拒绝
        assertThat(postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), sessionB).path("code").asText())
                .as("非发送者应 403").isEqualTo("403");
        Session outsider = registerAndLogin("13800003001", "路人");
        assertThat(postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), outsider).path("code").asText())
                .as("会话外路人应 403").isEqualTo("403");

        // 超过 2 分钟窗口拒绝
        jdbcTemplate.update("UPDATE message SET create_time = DATE_SUB(NOW(), INTERVAL 3 MINUTE), update_time = NOW() " +
                "WHERE msg_id = '" + msgIdOf(roomId, 1) + "'");
        assertThat(postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), sessionA).path("code").asText())
                .as("超 2 分钟应拒绝").isEqualTo("500");
        assertThat(queryString("SELECT state FROM message WHERE room_id = '" + roomId + "' AND seq = 1")).isEqualTo("NORMAL");

        // 窗口内的边界未到仍可撤（另一条新消息）
        sendText(roomId, "cmid-2", "窗口内", sessionA);
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/messages/2/recall", Map.of(), sessionA), "窗口内撤回");

        // 不存在的 seq
        assertThat(postJson("/api/chat/rooms/" + roomId + "/messages/99/recall", Map.of(), sessionA).path("code").asText())
                .as("消息不存在应 404").isEqualTo("404");
    }

    @Test
    void reportMessageCreatesSingleTicketAndAuditProcessForceRecallsWithoutCredit() {
        String roomId = createDirectRoomViaApi();
        String messageId = sendText(roomId, "cmid-1", "加我微信拉你进群", sessionA);

        // 成员举报：author_id 冗余 + content 快照
        JsonNode submit = postJson("/api/moderation/reports",
                Map.of("targetType", "MESSAGE", "targetId", messageId, "reason", "涉嫌诈骗导流"), sessionB);
        assertSuccess(submit, "举报消息");
        String reportId = submit.path("data").asText();
        assertUuidId(reportId);

        assertThat(queryString("SELECT target_type FROM content_report WHERE report_id = '" + reportId + "'")).isEqualTo("MESSAGE");
        assertThat(queryString("SELECT author_id FROM content_report WHERE report_id = '" + reportId + "'")).isEqualTo(sessionA.userId());
        assertThat(queryString("SELECT content_snapshot FROM content_report WHERE report_id = '" + reportId + "'")).isEqualTo("加我微信拉你进群");

        // 一人一消息一单（uk_reporter_target INSERT IGNORE）
        assertSuccess(postJson("/api/moderation/reports",
                Map.of("targetType", "MESSAGE", "targetId", messageId, "reason", "再报一次"), sessionB), "重复举报");
        assertThat(queryLong("SELECT COUNT(1) FROM content_report WHERE target_type = 'MESSAGE' AND target_id = '" + messageId + "'"))
                .as("一人一消息一单").isEqualTo(1);

        // 非会话成员 / 本人消息拒绝
        Session outsider = registerAndLogin("13800003002", "路人乙");
        assertThat(postJson("/api/moderation/reports",
                Map.of("targetType", "MESSAGE", "targetId", messageId, "reason", "我不是成员"), outsider).path("code").asText())
                .as("非会话成员应 403").isEqualTo("403");
        assertThat(postJson("/api/moderation/reports",
                Map.of("targetType", "MESSAGE", "targetId", messageId, "reason", "举报自己"), sessionA).path("code").asText())
                .as("不能举报自己的消息").isEqualTo("400");

        // 审核员处理：强制撤回无窗口限制（先人为制造超窗）
        Session auditor = auditorLogin("13800003003", "审核员甲");
        jdbcTemplate.update("UPDATE message SET create_time = DATE_SUB(NOW(), INTERVAL 1 HOUR), update_time = NOW() " +
                "WHERE msg_id = '" + messageId + "'");
        assertSuccess(postJson("/api/moderation/reports/" + reportId + "/process", Map.of("action", "PROCESSED"), auditor), "核实处理");

        assertThat(queryString("SELECT state FROM message WHERE msg_id = '" + messageId + "'")).isEqualTo("RECALLED");
        assertThat(queryString("SELECT recalled_by FROM message WHERE msg_id = '" + messageId + "'")).isEqualTo("AUDITOR");
        assertThat(queryLong("SELECT COUNT(1) FROM user_credit_detail WHERE user_id = '" + sessionA.userId() + "'" ))
                .as("chat 消息违规不走扣分流水").isEqualTo(0);

        // 双方通知沿用 REPORT_RESULT
        assertThat(queryLong("SELECT COUNT(1) FROM notification WHERE user_id = '" + sessionA.userId() + "'"
                + " AND type = 'REPORT_RESULT'")).as("被举报人通知").isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(1) FROM notification WHERE user_id = '" + sessionB.userId() + "'"
                + " AND type = 'REPORT_RESULT'")).as("举报人通知").isEqualTo(1);

        // 处理后读路径同样脱敏
        JsonNode history = getJson("/api/chat/rooms/" + roomId + "/messages", sessionB);
        assertThat(findMessageBySeq(history, 1).path("content").isNull()).as("强撤后同样脱敏").isTrue();
    }

    @Test
    void reportCardMessageSnapshotsRefIdentifier() {
        String roomId = createDirectRoomViaApi();
        String postId = publishPost(sessionA, "出闲置自行车");
        String messageId = sendCard(roomId, postId, sessionA);

        JsonNode submit = postJson("/api/moderation/reports",
                Map.of("targetType", "MESSAGE", "targetId", messageId, "reason", "虚假卡片"), sessionB);
        assertSuccess(submit, "举报卡片消息");
        assertThat(queryString("SELECT content_snapshot FROM content_report WHERE report_id = '" + submit.path("data").asText() + "'"))
                .as("CARD 存 ref 标识").isEqualTo("CARD:POST#" + postId);
    }

    @Test
    void processReportOnSelfRecalledMessageIsIdempotent() {
        String roomId = createDirectRoomViaApi();
        String messageId = sendText(roomId, "cmid-1", "我先自己撤了", sessionA);

        // 发送者已自行撤回，他人仍可举报（追责留痕），处理时幂等只落结论
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), sessionA), "自行撤回");
        JsonNode submit = postJson("/api/moderation/reports",
                Map.of("targetType", "MESSAGE", "targetId", messageId, "reason", "撤回也追责"), sessionB);
        String reportId = submit.path("data").asText();

        Session auditor = auditorLogin("13800003004", "审核员乙");
        assertSuccess(postJson("/api/moderation/reports/" + reportId + "/process", Map.of("action", "PROCESSED"), auditor), "处理已撤回消息");

        assertThat(queryString("SELECT recalled_by FROM message WHERE msg_id = '" + messageId + "'"))
                .as("已自行撤回保持 USER 撤回者不覆盖").isEqualTo("USER");
        assertThat(queryLong("SELECT COUNT(1) FROM outbox_message WHERE destination = '" + Constants.CHAT_RECALL_TOPIC + "'"))
                .as("幂等跳过不重复投递 RECALL").isEqualTo(1);
    }

    // ---------- helpers ----------

    private Session sessionA;
    private Session sessionB;

    private String createDirectRoomViaApi() {
        sessionA = registerAndLogin("13800004001", "撤回用户A");
        sessionB = registerAndLogin("13800004002", "撤回用户B");
        JsonNode response = postJson("/api/chat/rooms/direct", Map.of("targetUserId", sessionB.userId()), sessionA);
        assertSuccess(response, "建 1v1 会话");
        String roomId = response.path("data").path("roomId").asText();
        assertUuidId(roomId); // uuid-biz-id 票 04：roomId 必须是可解析的 UUID 字符串
        return roomId;
    }

    private String sendText(String roomId, String clientMsgId, String content, Session sender) {
        JsonNode response = postJson("/api/chat/rooms/" + roomId + "/messages",
                Map.of("clientMsgId", clientMsgId, "msgType", "TEXT", "content", content), sender);
        assertSuccess(response, "发送消息 " + clientMsgId);
        String messageId = response.path("data").path("messageId").asText();
        assertUuidId(messageId); // uuid-biz-id 票 04：messageId 必须是可解析的 UUID 字符串
        return messageId;
    }

    private String sendCard(String roomId, String postId, Session sender) {
        JsonNode response = postJson("/api/chat/rooms/" + roomId + "/messages",
                Map.of("clientMsgId", postId, "msgType", "CARD", "refType", "POST", "refId", postId), sender); // postId 是 36 位 UUID，直接充当 clientMsgId（≤36 限制）
        assertSuccess(response, "发送卡片消息");
        return response.path("data").path("messageId").asText();
    }

    private String msgIdOf(String roomId, long seq) {
        return queryString("SELECT msg_id FROM message WHERE room_id = '" + roomId + "' AND seq = " + seq);
    }

    /** 审核员登录：JWT 的 role 是签发快照，改库后需重新登录 */
    private Session auditorLogin(String phone, String nickname) {
        Session registered = registerAndLogin(phone, nickname);
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", phone), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + phone).get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", phone, "code", code), null);
        assertSuccess(loginResponse, "审核员重新登录");
        JsonNode data = loginResponse.path("data");
        return new Session(data.path("token").asText(), data.path("userInfo").path("userId").asText());
    }

    private JsonNode findMessageBySeq(JsonNode historyResponse, long seq) {
        for (JsonNode node : historyResponse.path("data")) {
            if (node.path("seq").asLong() == seq) {
                return node;
            }
        }
        throw new AssertionError("历史消息中找不到 seq=" + seq);
    }

    private long queryLong(String sql) {
        return ((Number) jdbcTemplate.queryForObject(sql, Object.class)).longValue();
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
