package com.betterschool.integration.chat;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chat 读路径集成测试（落地票 03）：单一 seam = REST 读端点 + 外部可观察状态（水位线/outbox/Redis）。
 * 覆盖：会话列表拼装与隐藏弹回、历史 beforeSeq 分页边界与撤回脱敏、补拉游标口径、
 * 水位线幂等无噪音 READ、成员含水位线、ws-ticket 原子一次性
 */
public class ChatReadPathIntegrationTest extends CommunityApiTestBase {

    private Session userA;
    private Session userB;
    private String roomId;

    @Test
    void roomListAssemblesSummaryHidesAndPopsBack() {
        initDirectRoom("列表用户A", "列表用户B");
        sendText("m1", "第一条");
        sendText("m2", "第二条");

        // B 视角：1v1 拼对方资料，unread = room.last_seq − 水位线（服务端拼装）
        JsonNode listForB = listRooms(userB);
        assertThat(listForB.path("total").asLong()).isEqualTo(1);
        JsonNode roomForB = listForB.path("list").get(0);
        assertThat(roomForB.path("roomId").asText()).isEqualTo(roomId);
        assertThat(roomForB.path("peerUserId").asText()).isEqualTo(userA.userId());
        assertThat(roomForB.path("peerNickname").asText()).isEqualTo("列表用户A");
        assertThat(roomForB.path("unread").asLong()).as("last_seq=2 − 水位线 0").isEqualTo(2);
        assertThat(roomForB.path("lastMsgPreview").asText()).isEqualTo("第二条");
        assertThat(roomForB.path("lastMsgType").asText()).isEqualTo("TEXT");

        // 发送者视角读后再看：unread 归零
        postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userA);
        JsonNode listForA = listRooms(userA);
        assertThat(listForA.path("list").get(0).path("unread").asLong()).isZero();

        // A 隐藏会话：自己列表消失，B 不受影响
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/hide", Map.of(), userA), "隐藏会话");
        assertThat(listRooms(userA).path("total").asLong()).as("隐藏后不进列表").isZero();
        assertThat(listRooms(userB).path("total").asLong()).isEqualTo(1);

        // 新消息弹回（微信行为）：B 再发一条，A 的会话重新出现且未读 +1
        sendText("m3", "弹回测试");
        JsonNode listAfter = listRooms(userA);
        assertThat(listAfter.path("total").asLong()).as("新消息清空 hidden_at 弹回").isEqualTo(1);
        assertThat(listAfter.path("list").get(0).path("unread").asLong()).isEqualTo(1);
    }

    @Test
    void roomListOrdersByLastMsgAtAcrossRooms() {
        initDirectRoom("排序用户A", "排序用户B");
        String room1 = roomId;
        Session userC = registerAndLogin("13800003003", "排序用户C");
        roomId = createDirectRoom(userA, userC.userId());
        String room2 = roomId;

        sendText("r1m1", "room1 消息");
        sendTextInRoom(room2, "r2m1", "room2 消息");

        List<String> roomIds = listRooms(userA).path("list").findValues("roomId").stream()
                .map(JsonNode::asText).toList();
        assertThat(roomIds).as("last_msg_time 倒序：最近发言的 room2 在前").containsExactly(room2, room1);
    }

    @Test
    void historyPagingBoundariesAndRecallDesensitize() {
        initDirectRoom("历史用户A", "历史用户B");
        for (int i = 1; i <= 5; i++) {
            sendText("h" + i, "历史消息" + i);
        }

        // 默认最新 50 条：全量返回，页内 seq 升序
        JsonNode full = getJson("/api/chat/rooms/" + roomId + "/messages", userB);
        assertSuccess(full, "历史默认页");
        assertThat(seqList(full)).containsExactly(1L, 2L, 3L, 4L, 5L);

        // beforeSeq 倒序分页：limit=2 取 [4,5]（页内升序），游标回退
        JsonNode page1 = getJson("/api/chat/rooms/" + roomId + "/messages?beforeSeq=6&limit=2", userB);
        assertThat(seqList(page1)).containsExactly(4L, 5L);
        JsonNode page2 = getJson("/api/chat/rooms/" + roomId + "/messages?beforeSeq=4&limit=2", userB);
        assertThat(seqList(page2)).containsExactly(2L, 3L);
        JsonNode page3 = getJson("/api/chat/rooms/" + roomId + "/messages?beforeSeq=2&limit=2", userB);
        assertThat(seqList(page3)).as("不足一页即到头").containsExactly(1L);

        // 撤回脱敏「已撤回」空壳：content/ref 不出服务端（库内保留取证）
        jdbcTemplate.update("UPDATE message SET state = 'RECALLED', recalled_at = NOW(), recalled_by = 'USER', " +
                "update_time = NOW() WHERE room_id = '" + roomId + "' AND seq = 3");
        JsonNode afterRecall = getJson("/api/chat/rooms/" + roomId + "/messages", userB);
        JsonNode recalled = afterRecall.path("data").get(2);
        assertThat(recalled.path("seq").asLong()).isEqualTo(3);
        assertThat(recalled.path("status").asText()).isEqualTo("RECALLED");
        assertThat(recalled.path("content").isNull()).as("撤回消息 content 不出服务端").isTrue();
        assertThat(afterRecall.path("data").get(1).path("status").asText()).isEqualTo("NORMAL");

        // 非成员拒绝
        Session outsider = registerAndLogin("13800003004", "历史路人");
        assertThat(getJson("/api/chat/rooms/" + roomId + "/messages", outsider).path("code").asText()).isEqualTo("403");
    }

    @Test
    void timelinePullCursorBoundaries() {
        initDirectRoom("补拉用户A", "补拉用户B");
        for (int i = 1; i <= 3; i++) {
            sendText("t" + i, "补拉消息" + i);
        }

        // 游标 0 = 全量：deliverySeq 升序、消息全量（roomId/seq/昵称）齐备
        JsonNode full = getJson("/api/chat/timeline", userB);
        assertSuccess(full, "补拉全量");
        assertThat(full.path("data").size()).isEqualTo(3);
        List<Long> deliverySeqs = full.findValues("deliverySeq").stream().map(JsonNode::asLong).toList();
        assertThat(deliverySeqs).as("per-user delivery_seq 从 1 起单调").containsExactly(1L, 2L, 3L);
        JsonNode first = full.path("data").get(0);
        assertThat(first.path("roomId").asText()).isEqualTo(roomId);
        assertThat(first.path("seq").asLong()).isEqualTo(1);
        assertThat(first.path("content").asText()).isEqualTo("补拉消息1");
        assertThat(first.path("fromNickname").asText()).isEqualTo("补拉用户A");

        // 下发与读路径同口径：补拉里已撤回消息同样脱敏空壳（spec §1）
        jdbcTemplate.update("UPDATE message SET state = 'RECALLED', recalled_at = NOW(), recalled_by = 'USER', " +
                "update_time = NOW() WHERE room_id = '" + roomId + "' AND seq = 2");
        JsonNode afterRecall = getJson("/api/chat/timeline", userB);
        JsonNode recalledItem = afterRecall.path("data").get(1);
        assertThat(recalledItem.path("seq").asLong()).isEqualTo(2);
        assertThat(recalledItem.path("status").asText()).isEqualTo("RECALLED");
        assertThat(recalledItem.path("content").isNull()).as("补拉不出已撤回 content").isTrue();

        // afterDeliverySeq 增量：只回游标之后，不足一页即追平
        JsonNode after2 = getJson("/api/chat/timeline?afterDeliverySeq=2", userB);
        assertThat(after2.findValues("deliverySeq").stream().map(JsonNode::asLong).toList()).containsExactly(3L);

        // limit 分页：第一页 2 条 + 第二页 1 条，循环拉语义
        JsonNode page1 = getJson("/api/chat/timeline?limit=2", userB);
        assertThat(page1.path("data").size()).isEqualTo(2);
        JsonNode page2 = getJson("/api/chat/timeline?afterDeliverySeq=2&limit=2", userB);
        assertThat(page2.path("data").size()).isEqualTo(1);

        // 多会话按 delivery_seq 全局归并（跨 room 单调）
        Session userC = registerAndLogin("13800003005", "补拉用户C");
        String otherRoom = createDirectRoom(userA, userC.userId());
        sendTextInRoom(otherRoom, "t4", "另一个会话");
        JsonNode merged = getJson("/api/chat/timeline", userA);
        List<String> rooms = merged.findValues("roomId").stream().map(JsonNode::asText).toList();
        List<Long> cursorSeqs = merged.findValues("deliverySeq").stream().map(JsonNode::asLong).toList();
        assertThat(rooms).as("两个会话都出现在补拉流").contains(roomId, otherRoom);
        assertThat(cursorSeqs).as("跨会话按投递序全局单调").isSorted();

        // 非登录用户由安全层拦截，无游标越权面：游标只按本人 user_id 查
        JsonNode foreign = getJson("/api/chat/timeline?afterDeliverySeq=0", userC);
        assertThat(foreign.path("data").size()).as("C 只见自己的 timeline").isEqualTo(1);

        // limit 硬顶 500（spec §2）：造到 505 行 timeline，limit=1000 也只回 500
        String msgId = queryString("SELECT msg_id FROM message WHERE room_id = '" + roomId + "' AND seq = 1");
        jdbcTemplate.update("INSERT INTO user_seq (user_id, seq, create_time, update_time) VALUES (?, 505, NOW(), NOW()) ON DUPLICATE KEY UPDATE seq = 505",
                userB.userId());
        List<String> rows = new ArrayList<>();
        for (int i = 4; i <= 505; i++) {
            rows.add("('" + userB.userId() + "', '" + roomId + "', 1, '" + msgId + "', " + i + ", NOW(), NOW())");
        }
        jdbcTemplate.update("INSERT INTO timeline (user_id, room_id, seq, msg_id, delivery_seq, create_time, update_time) VALUES "
                + String.join(",", rows));
        JsonNode capped = getJson("/api/chat/timeline?afterDeliverySeq=0&limit=1000", userB);
        assertSuccess(capped, "补拉超顶请求");
        assertThat(capped.path("data").size()).as("服务端硬顶 500").isEqualTo(500);
    }

    @Test
    void markReadIdempotentNoNoiseReadEvents() {
        initDirectRoom("已读用户A", "已读用户B");
        sendText("rm1", "已读测试1");
        sendText("rm2", "已读测试2");

        // B 打开会话：水位线推到会话真源 room.last_seq，提交后一个 READ 事件
        JsonNode readResp = postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userB);
        assertSuccess(readResp, "推水位线");
        assertThat(readResp.path("data").asLong()).isEqualTo(2);
        assertThat(queryLong("SELECT read_seq FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND user_id = '" + userB.userId() + "'" )).isEqualTo(2);

        assertThat(readEventCount()).as("首次打开恰好一个 READ 事件").isEqualTo(1);
        String payload = queryString("SELECT payload FROM outbox_message WHERE destination = '"
                + Constants.CHAT_READ_TOPIC + "'");
        assertThat(payload).contains("\"roomId\":\"" + roomId + "\"")
                .contains("\"userId\":\"" + userB.userId() + "\"")
                .contains("\"readSeq\":2");

        // 重复打开幂等：守卫 UPDATE 落空，无第二个 READ 事件（无噪音）
        postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userB);
        postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userB);
        assertThat(readEventCount()).as("重复打开不产生 READ 事件").isEqualTo(1);
        assertThat(queryLong("SELECT read_seq FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND user_id = '" + userB.userId() + "'" )).isEqualTo(2);

        // 落后目标（水位线已高）同样不回退不产事件：B 水位线=2，手动压低后重读只前进
        jdbcTemplate.update("UPDATE user_room SET read_seq = 1 WHERE room_id = '" + roomId + "' AND user_id = '" + userB.userId() + "'");
        postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userB);
        assertThat(readEventCount()).isEqualTo(2);
        assertThat(queryLong("SELECT read_seq FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND user_id = '" + userB.userId() + "'" )).as("只前进不回退").isEqualTo(2);

        // 非成员拒绝
        Session outsider = registerAndLogin("13800003006", "已读路人");
        assertThat(postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), outsider).path("code").asText()).isEqualTo("403");
    }

    @Test
    void membersCarryWatermarkAndTicketSingleUse() {
        initDirectRoom("成员用户A", "成员用户B");
        sendText("mm1", "成员测试1");
        postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userB);

        JsonNode members = getJson("/api/chat/rooms/" + roomId + "/members", userA);
        assertSuccess(members, "成员列表");
        assertThat(members.path("data").path("memberCount").asLong()).isEqualTo(2);
        Map<String, JsonNode> byUser = StreamSupport.stream(members.path("data").path("members").spliterator(), false)
                .collect(Collectors.toMap(node -> node.path("userId").asText(), node -> node));
        assertThat(byUser.get(userB.userId()).path("lastReadSeq").asLong()).as("B 已读水位线").isEqualTo(1);
        assertThat(byUser.get(userB.userId()).path("nickname").asText()).isEqualTo("成员用户B");
        assertThat(byUser.get(userA.userId()).path("lastReadSeq").asLong()).isZero();

        // 非成员拒绝（不出成员页）
        Session outsider = registerAndLogin("13800003007", "成员路人");
        assertThat(getJson("/api/chat/rooms/" + roomId + "/members", outsider).path("code").asText()).isEqualTo("403");
        assertThat(postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), outsider).path("code").asText()).isEqualTo("403");

        // ws-ticket：一次性原子取删
        JsonNode ticketResp = postJson("/api/chat/ws-ticket", Map.of(), userA);
        assertSuccess(ticketResp, "签发票据");
        String ticket = ticketResp.path("data").path("ticket").asText();
        assertThat(ticket).isNotBlank();
        String bound = redissonClient.<String>getBucket(Constants.WS_TICKET_KEY + ticket).get();
        assertThat(bound).as("票据绑定签发人").isEqualTo(userA.userId());

        assertThat(redissonClient.<String>getBucket(Constants.WS_TICKET_KEY + ticket).getAndDelete())
                .isEqualTo(userA.userId());
        assertThat(redissonClient.<String>getBucket(Constants.WS_TICKET_KEY + ticket).getAndDelete())
                .as("重放已用票据取不到（原子取删）").isNull();
    }

    // ---------- helpers ----------

    private void initDirectRoom(String nicknameA, String nicknameB) {
        userA = registerAndLogin("13800003001", nicknameA);
        userB = registerAndLogin("13800003002", nicknameB);
        roomId = createDirectRoom(userA, userB.userId());
    }

    private String createDirectRoom(Session from, String targetUserId) {
        JsonNode response = postJson("/api/chat/rooms/direct", Map.of("targetUserId", targetUserId), from);
        assertSuccess(response, "建 1v1 会话");
        return response.path("data").path("roomId").asText();
    }

    private void sendText(String clientMsgId, String content) {
        sendTextInRoom(roomId, clientMsgId, content);
    }

    private void sendTextInRoom(String targetRoomId, String clientMsgId, String content) {
        JsonNode response = postJson("/api/chat/rooms/messages", Map.of(
                "roomId", targetRoomId, "clientMsgId", clientMsgId, "msgType", "TEXT", "content", content), userA);
        assertSuccess(response, "发送消息 " + clientMsgId);
    }

    private JsonNode listRooms(Session session) {
        JsonNode response = getJson("/api/chat/rooms", session);
        assertSuccess(response, "会话列表");
        return response.path("data");
    }

    private List<Long> seqList(JsonNode response) {
        return StreamSupport.stream(response.path("data").spliterator(), false)
                .map(node -> node.path("seq").asLong())
                .collect(Collectors.toList());
    }

    private long readEventCount() {
        return queryLong("SELECT COUNT(1) FROM outbox_message WHERE destination = '" + Constants.CHAT_READ_TOPIC + "'");
    }

    private long queryLong(String sql) {
        return ((Number) jdbcTemplate.queryForObject(sql, Object.class)).longValue();
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
