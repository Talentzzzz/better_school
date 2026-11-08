package com.betterschool.integration.chat;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.TimelineEntity;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chat 发送主链路集成测试（落地票 02）：单一 seam = REST 两端点 + 外部可观察状态（五表 + outbox）。
 * 覆盖：建/取 1v1 幂等、发送五表落地、撞 uk 两时序收敛、hidden_at 弹回、列表预览读时现算
 * （票 02 预览去副本：user_room 只落 last_msg_time，预览按 message 真相渲染）、非成员/载荷校验拒绝
 */
public class ChatSendPipelineIntegrationTest extends CommunityApiTestBase {

    @Resource
    private IChatRepository chatRepository;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Value("${minio.public-url}")
    private String imagePublicUrl;

    @Test
    void createDirectRoomIdempotent_bothDirectionsReturnSameRoom() {
        Session userA = registerAndLogin("13800001001", "用户A");
        Session userB = registerAndLogin("13800001002", "用户B");

        JsonNode first = postJson("/api/chat/rooms/direct", Map.of("targetUserId", userB.userId()), userA);
        assertSuccess(first, "A 建 1v1 会话");
        String roomId = first.path("data").path("roomId").asText();
        assertUuidId(roomId); // uuid-biz-id 票 04：roomId 必须是可解析的 UUID 字符串

        // 反向建（B 找 A）与重复建都收敛同一 room（user_pair_room.uk_pair 幂等）
        JsonNode reverse = postJson("/api/chat/rooms/direct", Map.of("targetUserId", userA.userId()), userB);
        JsonNode repeat = postJson("/api/chat/rooms/direct", Map.of("targetUserId", userB.userId()), userA);
        assertSuccess(reverse, "B 反向建会话");
        assertSuccess(repeat, "A 重复建会话");
        assertThat(reverse.path("data").path("roomId").asText()).as("反向建返回同一 room").isEqualTo(roomId);
        assertThat(repeat.path("data").path("roomId").asText()).as("重复建返回同一 room").isEqualTo(roomId);
        assertThat(queryLong("SELECT COUNT(1) FROM user_room m1 JOIN user_room m2 ON m1.room_id = m2.room_id"
                + " WHERE m1.user_id = '" + (userA.userId().compareTo(userB.userId()) < 0 ? userA.userId() : userB.userId()) + "'"
                + " AND m2.user_id = '" + (userA.userId().compareTo(userB.userId()) < 0 ? userB.userId() : userA.userId()) + "'")).as("同人唯一会话").isEqualTo(1);

        // 双方名册行与用户行就位
        assertThat(queryLong("SELECT COUNT(1) FROM room_member WHERE room_id = '" + roomId + "'")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'")).isEqualTo(2);

        // 不能和自己建会话
        JsonNode self = postJson("/api/chat/rooms/direct", Map.of("targetUserId", userA.userId()), userA);
        assertThat(self.path("code").asText()).as("与自己建会话应被拒").isEqualTo("400");

        // 目标用户不存在
        JsonNode ghost = postJson("/api/chat/rooms/direct", Map.of("targetUserId", "00000000-0000-0000-0000-000000000000"), userA);
        assertThat(ghost.path("code").asText()).as("目标用户不存在应 404").isEqualTo("404");
    }

    @Test
    void sendMessagePersistsFiveTablesAndOutbox() {
        String roomId = createDirectRoomViaApi();

        JsonNode response = send(roomId, Map.of(
                "clientMsgId", "cmid-1",
                "msgType", "TEXT",
                "content", "同学你好，帖子还在吗？"), sessionA);
        assertSuccess(response, "发送文本消息");
        JsonNode data = response.path("data");
        assertThat(data.path("roomId").asText()).isEqualTo(roomId);
        assertThat(data.path("seq").asLong()).as("首条消息 seq 从 1 起").isEqualTo(1);
        assertThat(data.path("clientMsgId").asText()).isEqualTo("cmid-1");
        assertThat(data.path("msgType").asText()).isEqualTo("TEXT");

        // room：水位推进；列表排序键（last_msg_time）落用户维度 user_room（预览不落副本，读时现算）
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND last_msg_time IS NOT NULL")).isEqualTo(2);

        // message：内容中心一行
        assertThat(queryLong("SELECT COUNT(1) FROM message WHERE room_id = '" + roomId + "'")).isEqualTo(1);
        assertThat(queryString("SELECT client_msg_id FROM message WHERE room_id = '" + roomId + "'")).isEqualTo("cmid-1");

        // timeline：1v1 扇出 2 行，per-user delivery_seq 各自从 1 起
        assertThat(queryLong("SELECT COUNT(1) FROM timeline WHERE room_id = '" + roomId + "'")).as("扇出行数=成员数").isEqualTo(2);
        assertThat(queryLong("SELECT delivery_seq FROM timeline WHERE room_id = '" + roomId + "' AND user_id = '" + sessionA.userId() + "'" )).isEqualTo(1);
        assertThat(queryLong("SELECT delivery_seq FROM timeline WHERE room_id = '" + roomId + "' AND user_id = '" + sessionB.userId() + "'" )).isEqualTo(1);

        // outbox：MSG 事件与业务同事务落库，载荷含判重键与发送者信息
        assertThat(queryLong("SELECT COUNT(1) FROM outbox_message WHERE destination = '" + Constants.CHAT_PUSH_TOPIC + "'")).isEqualTo(1);
        String payload = queryString("SELECT payload FROM outbox_message WHERE destination = '" + Constants.CHAT_PUSH_TOPIC + "'");
        assertThat(payload).contains("\"roomId\":\"" + roomId + "\"").contains("\"seq\":1").contains("会话用户A");
    }

    @Test
    void duplicateClientMsgIdConvergesToSameMessage() {
        String roomId = createDirectRoomViaApi();

        JsonNode first = send(roomId, Map.of(
                "clientMsgId", "cmid-dup", "msgType", "TEXT", "content", "第一条"), sessionA);
        assertSuccess(first, "首发");
        long firstSeq = first.path("data").path("seq").asLong();

        // 双击/超时重发复用同 clientMsgId：撞 uk_client_msg 回查收敛同一条
        JsonNode second = send(roomId, Map.of(
                "clientMsgId", "cmid-dup", "msgType", "TEXT", "content", "第一条"), sessionA);
        assertSuccess(second, "重复提交幂等收敛");
        assertThat(second.path("data").path("seq").asLong()).as("同 seq 无重复行").isEqualTo(firstSeq);
        assertThat(second.path("data").path("messageId").asText()).isEqualTo(first.path("data").path("messageId").asText());

        assertThat(queryLong("SELECT COUNT(1) FROM message WHERE room_id = '" + roomId + "'")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(1) FROM timeline WHERE room_id = '" + roomId + "'")).as("不重复扇出").isEqualTo(2);
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).as("seq 无空洞").isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(1) FROM outbox_message WHERE destination = '" + Constants.CHAT_PUSH_TOPIC + "'")).as("不重复写推送事件").isEqualTo(1);
    }

    @Test
    void concurrentUncommittedDuplicateConvergesAfterRollback() throws Exception {
        String roomId = createDirectRoomViaApi();

        // 未落库时序：并发事务持 uk 锁未提交，发送事务插入阻塞 → 撞 uk 回滚 → 回查收敛原消息
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> holderMsgId = new AtomicReference<>();
        Thread holder = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            long seq = chatRepository.increaseLastSeq(roomId);
            MessageEntity messageEntity = new MessageEntity();
            messageEntity.setRoomId(roomId);
            messageEntity.setSeq(seq);
            messageEntity.setFromUserId(sessionA.userId());
            messageEntity.setMsgType(MsgTypeVO.TEXT);
            messageEntity.setContent("并发先行者");
            messageEntity.setClientMsgId("cmid-race");
            chatRepository.insertMessage(messageEntity);
            holderMsgId.set(messageEntity.getMessageId());
            long deliveryA = chatRepository.increaseUserSeq(sessionA.userId(), 1);
            long deliveryB = chatRepository.increaseUserSeq(sessionB.userId(), 1);
            chatRepository.insertTimelines(List.of(
                    timeline(sessionA.userId(), roomId, seq, messageEntity.getMessageId(), deliveryA),
                    timeline(sessionB.userId(), roomId, seq, messageEntity.getMessageId(), deliveryB)));
            inserted.countDown();
            try {
                // 行锁保持到提交：模拟另一发送事务尚未落库
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        // 定时释放：先让发送请求进入等锁，再提交先行事务（TestRestTemplate 读超时 10s，等待须短于它）
        java.util.concurrent.ScheduledExecutorService releaser =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        releaser.schedule(release::countDown, 1500, TimeUnit.MILLISECONDS);

        JsonNode response = send(roomId, Map.of(
                "clientMsgId", "cmid-race", "msgType", "TEXT", "content", "并发后来者"), sessionA);
        holder.join(10000);
        releaser.shutdownNow();

        assertSuccess(response, "撞未落库事务后回查收敛");
        assertThat(response.path("data").path("messageId").asText()).as("收敛到原消息").isEqualTo(holderMsgId.get());
        assertThat(response.path("data").path("content").asText()).isEqualTo("并发先行者");
        assertThat(queryLong("SELECT COUNT(1) FROM message WHERE room_id = '" + roomId + "'")).isEqualTo(1);
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).as("回滚不留空洞").isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(1) FROM timeline WHERE room_id = '" + roomId + "'")).isEqualTo(2);
    }

    @Test
    void newMessagePopsHiddenRoomBackToList() {
        String roomId = createDirectRoomViaApi();
        jdbcTemplate.update("UPDATE user_room SET hidden_at = NOW() WHERE room_id = '" + roomId + "' AND user_id = '" + sessionB.userId() + "'");

        send(roomId, Map.of(
                "clientMsgId", "cmid-pop", "msgType", "TEXT", "content", "在吗"), sessionA);

        Object hiddenAt = query("SELECT hidden_at FROM user_room WHERE room_id = '" + roomId + "' AND user_id = '" + sessionB.userId() + "'");
        assertThat(hiddenAt).as("新消息清空扇出目标 hidden_at 弹回").isNull();
        // 未隐藏的一方不受影响（本来就为空）
    }

    @Test
    void lastMsgPreviewRendersFromMessageTruthOnRead() {
        String roomId = createDirectRoomViaApi();

        String imageContent = "{\"url\":\"" + imagePublicUrl + "/chat/p1.jpg\",\"width\":800,\"height\":600}";
        JsonNode imageResp = send(roomId, Map.of(
                "clientMsgId", "cmid-img", "msgType", "IMAGE", "content", imageContent), sessionA);
        assertSuccess(imageResp, "发送图片消息");
        // 预览不落副本（票 02）：读列表时按 message 真相现算渲染
        JsonNode listAfterImage = listRooms(sessionB);
        assertThat(listAfterImage.path("list").get(0).path("lastMsgPreview").asText()).isEqualTo("[图片]");
        assertThat(listAfterImage.path("list").get(0).path("lastMsgType").asText()).isEqualTo("IMAGE");

        String postId = publishPost(sessionA, "出闲置书");
        JsonNode cardResp = send(roomId, Map.of(
                "clientMsgId", "cmid-card", "msgType", "CARD", "refType", "POST", "refId", postId), sessionA);
        assertSuccess(cardResp, "发送卡片消息");
        assertThat(cardResp.path("data").path("content").isNull()).as("CARD content 恒空").isTrue();
        JsonNode listAfterCard = listRooms(sessionA);
        assertThat(listAfterCard.path("list").get(0).path("lastMsgPreview").asText())
                .as("CARD 按 ref_type 出类型标签，不查标题").isEqualTo("[帖子]");
        assertThat(listAfterCard.path("list").get(0).path("lastMsgType").asText()).isEqualTo("CARD");
        assertThat(queryString("SELECT ref_type FROM message WHERE room_id = '" + roomId + "' AND client_msg_id = 'cmid-card'")).isEqualTo("POST");

        // 两条消息 seq 连续推进
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).isEqualTo(2);

        // 外站图片 url 拒绝
        JsonNode evilImage = send(roomId, Map.of(
                "clientMsgId", "cmid-evil", "msgType", "IMAGE",
                "content", "{\"url\":\"http://evil.com/a.jpg\"}"), sessionA);
        assertThat(evilImage.path("code").asText()).as("非本站上传域前缀应拒绝").isEqualTo("400");

        // 已删帖子卡片拒绝（走正规删除路径，findById 有详情缓存，直改 DB 不踢缓存会绕过校验）
        JsonNode delResp = deleteJson("/api/community/posts/" + postId, sessionA);
        assertSuccess(delResp, "删除帖子");
        JsonNode deadCard = send(roomId, Map.of(
                "clientMsgId", "cmid-dead", "msgType", "CARD", "refType", "POST", "refId", postId), sessionA);
        assertThat(deadCard.path("code").asText()).as("已删对象卡片应拒绝（find 不出已删行）").isEqualTo("404");
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).as("失败消息不占 seq").isEqualTo(2);
    }

    @Test
    void sendRejectsNonMemberAndBadPayload() {
        String roomId = createDirectRoomViaApi();
        Session outsider = registerAndLogin("13800001003", "用户C");

        // 非成员拒绝
        JsonNode outsiderSend = send(roomId, Map.of(
                "clientMsgId", "cmid-out", "msgType", "TEXT", "content", "我是路人"), outsider);
        assertThat(outsiderSend.path("code").asText()).as("非会话成员应 403").isEqualTo("403");

        // TEXT 超长拒绝（≤500 单一常量）
        JsonNode tooLong = send(roomId, Map.of(
                "clientMsgId", "cmid-long", "msgType", "TEXT", "content", "长".repeat(501)), sessionA);
        assertThat(tooLong.path("code").asText()).as("超 500 字应拒绝").isEqualTo("400");

        // 上述失败均不占号不留痕
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).isEqualTo(0);
        assertThat(queryLong("SELECT COUNT(1) FROM message WHERE room_id = '" + roomId + "'")).isEqualTo(0);
    }

    // ---------- helpers ----------

    private Session sessionA;
    private Session sessionB;

    private String createDirectRoomViaApi() {
        sessionA = registerAndLogin("13800002001", "会话用户A");
        sessionB = registerAndLogin("13800002002", "会话用户B");
        JsonNode response = postJson("/api/chat/rooms/direct", Map.of("targetUserId", sessionB.userId()), sessionA);
        assertSuccess(response, "建 1v1 会话");
        String roomId = response.path("data").path("roomId").asText();
        assertUuidId(roomId); // uuid-biz-id 票 04：roomId 必须是可解析的 UUID 字符串
        return roomId;
    }

    /** 发送消息：roomId 收敛进请求体（POST /rooms/messages 契约） */
    private JsonNode send(String roomId, Map<String, Object> payload, Session sender) {
        java.util.Map<String, Object> body = new java.util.HashMap<>(payload);
        body.put("roomId", roomId);
        return postJson("/api/chat/rooms/messages", body, sender);
    }

    private TimelineEntity timeline(String userId, String roomId, long seq, String msgId, long deliverySeq) {
        TimelineEntity timelineEntity = new TimelineEntity();
        timelineEntity.setUserId(userId);
        timelineEntity.setRoomId(roomId);
        timelineEntity.setSeq(seq);
        timelineEntity.setMsgId(msgId);
        timelineEntity.setDeliverySeq(deliverySeq);
        return timelineEntity;
    }

    private JsonNode listRooms(Session session) {
        JsonNode response = getJson("/api/chat/rooms", session);
        assertSuccess(response, "会话列表");
        return response.path("data");
    }

    private Object query(String sql) {
        return jdbcTemplate.queryForObject(sql, Object.class);
    }

    private long queryLong(String sql) {
        return ((Number) jdbcTemplate.queryForObject(sql, Object.class)).longValue();
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
