package com.betterschool.integration.chat;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chat 用户自撤集成测试（落地票 05，审核/举报语义移除后翻新）：单一 seam = REST 端点（recall）+
 * 外部可观察状态（message/room/outbox/notification）。覆盖：撤回窗口与本人校验、撤回三件套与
 * 预览读时现算（票 02 预览去副本：撤回零 user_room 写、列表直接呈现 [已撤回]）、重复撤回幂等。
 * 举报/强制撤回/AUDITOR 语义已整体删除：recalled_by 只会是 'USER'。
 */
public class ChatRecallReportIntegrationTest extends PostApiTestBase {

    @Test
    void recallBySenderWithinWindowMarksShellAndPushesRecallEvent() {
        String roomId = createDirectRoomViaApi();
        String msg1 = sendText(roomId, "cmid-1", "第一条，等会儿撤回", sessionA);
        String msg2 = sendText(roomId, "cmid-2", "第二条，最新的", sessionA);

        // 冻结 user_room 行时间戳：撤回若碰任何 user_room 写路径（预览维护类），update_time 必然离开冻结值
        //（前提：user_room 现有全部写原语都显式刷 update_time=NOW()；将来新增不刷时间戳的写原语时此断言需同步补强）
        jdbcTemplate.update("UPDATE user_room SET update_time = '2000-01-01 00:00:00' WHERE room_id = '" + roomId + "'");

        // 撤回非最后一条：预览读时现算，仍按 message 真相渲染最新一条；user_room 行零写
        postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), sessionA);
        assertThat(listLastMsgPreview(sessionA)).as("列表预览 = 最新一条真相（无预览写路径可漂移）").isEqualTo("第二条，最新的");
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND update_time = '2000-01-01 00:00:00'")).as("撤回非最后一条不碰 user_room 行").isEqualTo(2);

        // 幂等：重复撤回静默成功，不重复写 RECALL 事件
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), sessionA), "重复撤回");
        assertThat(queryLong("SELECT COUNT(1) FROM outbox_message WHERE destination = '" + Constants.CHAT_RECALL_TOPIC + "'")).isEqualTo(1);

        // 撤回最后一条：预览读时直接呈现 [已撤回]（不经过任何预览写路径），占 seq 不回退、不动水位线（计入未读）
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/messages/2/recall", Map.of(), sessionA), "撤回最新消息");
        assertThat(listLastMsgPreview(sessionA)).as("A 列表读时现算 [已撤回]").isEqualTo("[已撤回]");
        assertThat(listLastMsgPreview(sessionB)).as("B 列表读时现算 [已撤回]").isEqualTo("[已撤回]");
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND update_time = '2000-01-01 00:00:00'")).as("撤回全程零 user_room 写（预览去副本）").isEqualTo(2);
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
        JsonNode response = postJson("/api/chat/rooms/messages",
                Map.of("roomId", roomId, "clientMsgId", clientMsgId, "msgType", "TEXT", "content", content), sender);
        assertSuccess(response, "发送消息 " + clientMsgId);
        String messageId = response.path("data").path("messageId").asText();
        assertUuidId(messageId); // uuid-biz-id 票 04：messageId 必须是可解析的 UUID 字符串
        return messageId;
    }

    private String msgIdOf(String roomId, long seq) {
        return queryString("SELECT msg_id FROM message WHERE room_id = '" + roomId + "' AND seq = " + seq);
    }

    /** 会话列表第一项的预览文案（预览读时现算断言入口） */
    private String listLastMsgPreview(Session session) {
        JsonNode response = getJson("/api/chat/rooms", session);
        assertSuccess(response, "会话列表");
        return response.path("data").path("list").get(0).path("lastMsgPreview").asText();
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
