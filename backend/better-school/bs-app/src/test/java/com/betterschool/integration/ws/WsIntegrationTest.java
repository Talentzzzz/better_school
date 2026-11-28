package com.betterschool.integration.ws;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.infrastructure.ws.WsLocalSessions;
import com.betterschool.infrastructure.ws.wsframe.WsFrame;
import com.betterschool.infrastructure.ws.wsframe.WsFrameTypeEnum;
import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.trigger.ws.WsConnectionConstants;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import jakarta.annotation.Resource;
import jakarta.websocket.DeploymentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * chat WS 全链路行为集成测试（落地票 07，spec §9）：真 WS 客户端直连 RANDOM_PORT，
 * 推送在写请求内同步完成（发送/已读/撤回的事务提交后直接投递），不再经过 MQ。
 *
 * <p>覆盖：握手 401 与票据一次性、单设备互踢 close(4001)、心跳 PING/PONG 与 60s 判死清理、
 * MSG 排除发送者+判重键 (roomId, seq)、群消息全员在线各一份、READ 水位线推送与幂等、
 * 断线期间已读不补推（REST 取初值）、断线补拉与实时推送按判重键合流、RECALL 轻推。
 *
 * <p>spec §2 异常路径表中「推送失败/掉线」「补拉与实时推送竞争」由本类用例背书；
 * 「RECALL 轻推丢失」「断线期间对方已读」为接受退化——语义由用例注释记档。
 * 发送超时重发/重复提交幂等由 {@link ChatSendPipelineIntegrationTest} 背书。
 */
public class WsIntegrationTest extends PostApiTestBase {

    @LocalServerPort
    private int port;

    @Resource
    private WsLocalSessions localSessions;

    @Resource
    private INotificationService notificationService;

    private StandardWebSocketClient wsClient;

    private final List<WsClient> openClients = new ArrayList<>();

    @BeforeEach
    void initWsClient() {
        wsClient = new StandardWebSocketClient();
        openClients.clear();
    }

    @AfterEach
    void closeWsClients() {
        openClients.forEach(WsClient::closeQuietly);
        openClients.clear();
    }

    // ---------- 1. 握手 ----------

    @Test
    void handshakeRejectsInvalidExpiredAndMissingTicket() {
        Session user = registerAndLogin("13800004001", "握手用户");

        // 未知票据
        assertHandshakeRejected(wsUrl("no-such-ticket"));

        // 过期票据：TTL 60s 由 Redis 承担，测试删除 key 模拟过期（不真等 60s）
        String expired = issueTicket(user);
        redissonClient.getBucket(Constants.WS_TICKET_KEY + expired).delete();
        assertHandshakeRejected(wsUrl(expired));

        // 无票据
        assertHandshakeRejected("ws://localhost:" + port + WsConnectionConstants.ENDPOINT);
    }

    @Test
    void handshakeTicketIsSingleUse() throws Exception {
        Session user = registerAndLogin("13800004002", "票据用户");
        String ticket = issueTicket(user);

        WsClient connection = connectWithTicket(ticket);
        assertThat(connection.isOpen()).isTrue();

        // 原子取删：同一票据第二次握手必被拒（重放防护）
        assertHandshakeRejected(wsUrl(ticket));
        assertThat(connection.isOpen()).as("重放失败不影响已建立的连接").isTrue();
    }

    // ---------- 2. 互踢 ----------

    @Test
    void secondConnectionKicksFirstWith4001AndLateCloseKeepsNew() throws Exception {
        Session userA = registerAndLogin("13800004011", "踢号用户A");
        Session userB = registerAndLogin("13800004012", "踢号用户B");
        String roomId = createDirectRoom(userA, userB.userId());

        WsClient first = connect(userA);
        WsClient second = connect(userA);

        // 旧连接先收 KICK（reason 供客户端提示重新登录），随后被 close(4001) 阻断自动重连
        WsFrame kick = awaitFrame(first, WsFrameTypeEnum.KICK, 5_000);
        assertThat(kick).as("旧连接应收到 KICK").isNotNull();
        assertThat(payloadOf(kick)).containsEntry("reason", WsConnectionConstants.KICK_REASON_SESSION_TAKEN_OVER);
        assertThat(first.awaitClose(5_000).getCode()).as("互踢关闭码 4001").isEqualTo(WsConnectionConstants.CLOSE_CODE_TAKEN_OVER);
        assertThat(second.isOpen()).as("新连接保持在线").isTrue();

        // 旧连接迟到 close 由两参注销挡住：新连接仍在注册表中（外部证据=还能收到推送）
        JsonNode sent = sendText(roomId, "kick-after", "踢号后的消息", userB);
        assertSuccess(sent, "B 发言");

        WsFrame pushed = awaitFrame(second, WsFrameTypeEnum.MSG, 5_000);
        assertThat(pushed).as("新连接未被旧连接迟到 close 误删").isNotNull();
        assertThat((String) payloadOf(pushed).get("roomId")).isEqualTo(roomId);
    }

    // ---------- 3. 心跳与判死 ----------

    @Test
    void pingPongAndSilentSessionSweptAsDead() throws Exception {
        Session user = registerAndLogin("13800004021", "心跳用户");
        WsClient connection = connect(user);

        connection.sendText("{\"type\":\"PING\"}");
        assertThat(awaitFrame(connection, WsFrameTypeEnum.PONG, 5_000)).as("PING 应得 PONG 应答").isNotNull();

        // 正向：刚入站（PING 刷新过活跃时间）的会话不被清扫误杀
        localSessions.sweepExpired(System.currentTimeMillis(), WsLocalSessions.DEAD_THRESHOLD_MS);
        assertThat(connection.isOpen()).as("入站刷新过的会话不判死").isTrue();

        // 判死=阈值内无入站：注入未来时钟驱动清扫，等价 60s 静默（真实等待不做）
        localSessions.sweepExpired(System.currentTimeMillis() + WsLocalSessions.DEAD_THRESHOLD_MS + 1_000,
                WsLocalSessions.DEAD_THRESHOLD_MS);

        CloseStatus status = connection.awaitClose(5_000);
        assertThat(status.getCode()).as("判死清理用 GOING_AWAY").isEqualTo(CloseStatus.GOING_AWAY.getCode());
    }

    // ---------- 4. MSG 推送 ----------

    @Test
    void directMessagePushExcludesSenderAndCarriesDedupKey() throws Exception {
        Session userA = registerAndLogin("13800004031", "推送用户A");
        Session userB = registerAndLogin("13800004032", "推送用户B");
        String roomId = createDirectRoom(userA, userB.userId());

        WsClient connA = connect(userA);
        WsClient connB = connect(userB);

        JsonNode sent = sendText(roomId, "ws-1", "实时消息", userA);
        assertSuccess(sent, "发送");

        // 接收端拿全量消息体：含判重键 (roomId, seq) 供与补拉结果合流
        WsFrame wsFrame = awaitFrame(connB, WsFrameTypeEnum.MSG, 5_000);
        assertThat(wsFrame).as("在线成员应收到 MSG").isNotNull();
        Map<String, Object> payload = payloadOf(wsFrame);
        assertThat((String) payload.get("roomId")).as("载荷含判重键 roomId").isEqualTo(roomId);
        assertThat(((Number) payload.get("seq")).longValue()).as("载荷含判重键 seq").isEqualTo(1L);
        assertThat(payload).containsEntry("content", "实时消息").containsEntry("fromNickname", "推送用户A");
        assertThat(payload).as("载荷含发送者完整资料槽位（头像可空但须带出）").containsKey("fromAvatar");

        // 发送方以 HTTP 200 为准，不做自推
        assertThat(drain(connA, 400)).as("发送者不自收").noneMatch(frame -> frame.getType() == WsFrameTypeEnum.MSG);
        assertThat(drain(connB, 300)).as("同一事件只推一份").noneMatch(frame -> frame.getType() == WsFrameTypeEnum.MSG);
    }

    // ---------- 5. READ 水位线推送 ----------

    @Test
    void readWatermarkPushedToOtherOnlineMembersOnce() throws Exception {
        Session userA = registerAndLogin("13800004051", "已读用户A");
        Session userB = registerAndLogin("13800004052", "已读用户B");
        String roomId = createDirectRoom(userA, userB.userId());
        sendText(roomId, "r-1", "已读测试1", userA);
        sendText(roomId, "r-2", "已读测试2", userA);

        WsClient connA = connect(userA);
        WsClient connB = connect(userB);

        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userB), "B 打开会话");

        WsFrame wsFrame = awaitFrame(connA, WsFrameTypeEnum.READ, 5_000);
        assertThat(wsFrame).as("其他在线成员应收到 READ").isNotNull();
        Map<String, Object> payload = payloadOf(wsFrame);
        assertThat((String) payload.get("roomId")).as("payload 含 roomId").isEqualTo(roomId);
        assertThat((String) payload.get("userId")).as("payload 含 readerId").isEqualTo(userB.userId());
        assertThat(((Number) payload.get("readSeq")).longValue()).as("水位线=room.last_seq").isEqualTo(2L);

        assertThat(drain(connB, 400)).as("reader 本人不收回执").noneMatch(frame -> frame.getType() == WsFrameTypeEnum.READ);

        // 重复打开幂等：写侧守卫 UPDATE 落空 → 不产生第二份推送
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userB), "B 重复打开");
        assertThat(drain(connA, 400)).as("无第二份 READ").noneMatch(frame -> frame.getType() == WsFrameTypeEnum.READ);
    }

    @Test
    void offlineReaderWatermarkRecoveredFromRestNotPush() throws Exception {
        Session userA = registerAndLogin("13800004061", "离线已读A");
        Session userB = registerAndLogin("13800004062", "离线已读B");
        String roomId = createDirectRoom(userA, userB.userId());
        sendText(roomId, "o-1", "离线测试1", userA);
        sendText(roomId, "o-2", "离线测试2", userA);

        // B 断线
        connect(userB).closeQuietly();

        // 断线期间对方（A）已读：推给 B 时 B 不在线 → 漏推合法（接受退化，不缓存不补推）
        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/read", Map.of(), userA), "A 打开会话");

        // 重连后水位线初值走 REST 读路径，不靠补推
        WsClient reconnected = connect(userB);
        JsonNode members = getJson("/api/chat/rooms/" + roomId + "/members", userB);
        assertSuccess(members, "成员列表");
        Map<String, JsonNode> byUser = StreamSupport.stream(members.path("data").path("members").spliterator(), false)
                .collect(Collectors.toMap(node -> node.path("userId").asText(), node -> node));
        assertThat(byUser.get(userA.userId()).path("lastReadSeq").asLong())
                .as("重连后从 REST 取到对方水位线").isEqualTo(2L);
        assertThat(drain(reconnected, 400)).as("断线期间已读不补推").noneMatch(frame -> frame.getType() == WsFrameTypeEnum.READ);
    }

    // ---------- 6. 断线重连：补拉与实时推送合流 ----------

    @Test
    void reconnectPullMergesWithRealtimePushByRoomSeqDedup() throws Exception {
        Session userA = registerAndLogin("13800004071", "重连用户A");
        Session userB = registerAndLogin("13800004072", "重连用户B");
        String roomId = createDirectRoom(userA, userB.userId());

        // B 先连后断：断线期间的推送全部落空
        connect(userB).closeQuietly();

        sendText(roomId, "c-1", "断线期间1", userA);
        sendText(roomId, "c-2", "断线期间2", userA);

        // 重连（内存游标随连接丢失 → 游标 0 = 全量，spec §2）
        WsClient reconnected = connect(userB);
        assertThat(drain(reconnected, 300)).as("断线期间推送已漏（漏推合法）").noneMatch(frame -> frame.getType() == WsFrameTypeEnum.MSG);

        // 期间实时推送
        sendText(roomId, "c-3", "重连后实时", userA);
        WsFrame wsFrame = awaitFrame(reconnected, WsFrameTypeEnum.MSG, 5_000);
        assertThat(wsFrame).as("重连后实时推送可达").isNotNull();
        assertThat(((Number) payloadOf(wsFrame).get("seq")).longValue()).isEqualTo(3L);

        // 补拉：游标 0 全量取回三条，每条带 (roomId, seq) 判重键 + delivery_seq 游标
        JsonNode timeline = getJson("/api/chat/timeline?afterDeliverySeq=0", userB);
        assertSuccess(timeline, "补拉全量");
        assertThat(timeline.path("data").path("hasMore").asBoolean()).as("不足一页即追平").isFalse();
        Set<String> pulledKeys = new LinkedHashSet<>();
        for (JsonNode item : timeline.path("data").path("list")) {
            assertThat(item.path("roomId").asText()).as("补拉条目带判重键 roomId").isEqualTo(roomId);
            assertThat(item.path("deliverySeq").asLong()).as("补拉条目带投递序号游标").isPositive();
            pulledKeys.add(item.path("roomId").asText() + ":" + item.path("seq").asLong());
        }
        assertThat(pulledKeys).as("断线期间两条 + 重连后一条，键唯一且无丢失")
                .containsExactly(roomId + ":1", roomId + ":2", roomId + ":3");

        // 合流：实时推送段的 (roomId, seq) 已在补拉结果中 → 客户端按此键吸收重复段，不丢不重
        assertThat(pulledKeys).as("推送段与补拉段判重键重合，可吸收")
                .contains(payloadOf(wsFrame).get("roomId") + ":" + payloadOf(wsFrame).get("seq"));
    }

    // ---------- 7. RECALL 轻推 ----------

    @Test
    void recallPushedToAllOnlineMembers() throws Exception {
        Session userA = registerAndLogin("13800004081", "撤回用户A");
        Session userB = registerAndLogin("13800004082", "撤回用户B");
        String roomId = createDirectRoom(userA, userB.userId());
        sendText(roomId, "x-1", "待撤回的消息", userA);

        WsClient connA = connect(userA);
        WsClient connB = connect(userB);

        assertSuccess(postJson("/api/chat/rooms/" + roomId + "/messages/1/recall", Map.of(), userA), "撤回");

        // RECALL 轻事件只带定位 payload={roomId, seq}：内容终态以拉取为准（轻推丢失可接受，重进/重连自然收敛）
        WsFrame forB = awaitFrame(connB, WsFrameTypeEnum.RECALL, 5_000);
        assertThat(forB).as("对方在线成员应收到 RECALL").isNotNull();
        assertThat((String) payloadOf(forB).get("roomId")).isEqualTo(roomId);
        assertThat(((Number) payloadOf(forB).get("seq")).longValue()).isEqualTo(1L);

        WsFrame forA = awaitFrame(connA, WsFrameTypeEnum.RECALL, 5_000);
        assertThat(forA).as("撤回推给会话全部在线成员（含发送者）").isNotNull();
        assertThat(((Number) payloadOf(forA).get("seq")).longValue()).isEqualTo(1L);
    }

    // ---------- 8. 通知横幅直推 ----------

    @Test
    void bannerNotificationPushedDirectlyAsNotifFrame() throws Exception {
        Session user = registerAndLogin("13800004091", "横幅用户");
        WsClient conn = connect(user);

        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(user.userId())
                .senderId("sender-1")
                .scope(NotificationScopeEnum.INTERACTION_LIKE.name())
                .type(NotificationTypeEnum.POST_LIKED.name())
                .title("收到的赞")
                .content("有人赞了你的帖子")
                .refType("POST")
                .refId("p1")
                .dedupKey("POST_LIKED:p1:sender-1")
                .build());

        // 直接喂通知事件消费方：落库后不再走第二跳 MQ，当场推 NOTIF 帧
        notifyEventConsumer.onMessage(outboxPayloads(Constants.NOTIFY_EVENT_TOPIC).get(0));

        WsFrame<?> frame = awaitFrame(conn, WsFrameTypeEnum.NOTIF, 5_000);
        assertThat(frame).as("BANNER 通知应实时推 NOTIF 帧").isNotNull();
        Map<String, Object> payload = payloadOf(frame);
        assertThat(payload.get("scope")).isEqualTo("INTERACTION_LIKE");
        assertThat(((Number) payload.get("seq")).longValue()).isEqualTo(1L);
        assertThat(payload.get("type")).isEqualTo("POST_LIKED");
        assertThat(payload.get("refId")).isEqualTo("p1");
    }

    // ---------- helpers: WS ----------

    private String wsUrl(String ticket) {
        return "ws://localhost:" + port + WsConnectionConstants.ENDPOINT + "?ticket=" + ticket;
    }

    private String issueTicket(Session user) {
        JsonNode response = postJson("/api/ws-ticket", Map.of(), user);
        assertSuccess(response, "签发票据");
        return response.path("data").path("ticket").asText();
    }

    private WsClient connect(Session user) throws Exception {
        return connectWithTicket(issueTicket(user));
    }

    /**
     * 建连 + PING/PONG 同步：握手 future 完成早于服务端 afterConnectionEstablished（注册表写入），
     * 同步一次往返确保后续「同账号再连」的互踢时序稳定
     */
    private WsClient connectWithTicket(String ticket) throws Exception {
        CollectingHandler handler = new CollectingHandler(objectMapper);
        WebSocketSession session = wsClient.execute(handler, wsUrl(ticket)).get(10, TimeUnit.SECONDS);
        WsClient client = new WsClient(session, handler);
        openClients.add(client);
        client.sendText("{\"type\":\"PING\"}");
        assertThat(awaitFrame(client, WsFrameTypeEnum.PONG, 5_000)).as("建连同步 PONG").isNotNull();
        return client;
    }

    /**
     * 握手拒绝：客户端把被拒的 HTTP 响应码（401）包进 DeploymentException 消息，
     * 遂借消息断言真实状态码——仅断言"抛异常"会把服务未起/超时等失败一并放过
     */
    private void assertHandshakeRejected(String url) {
        assertThatThrownBy(() -> wsClient.execute(new CollectingHandler(objectMapper), url).get(10, TimeUnit.SECONDS))
                .as("握手应被拒（401）：" + url)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("401");
    }

    private WsFrame<?> awaitFrame(WsClient client, WsFrameTypeEnum wsFrameType, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            WsFrame<?> frame = client.handler().frames.poll(remaining, TimeUnit.MILLISECONDS);
            if (frame == null) {
                return null;
            }
            if (frame.getType() == wsFrameType) {
                return frame;
            }
        }
    }

    /** 等待 waitMs 后收集队列中现有帧（等待窗口用于捕捉「本不该到达」的推送） */
    private List<WsFrame<?>> drain(WsClient client, long waitMs) throws InterruptedException {
        List<WsFrame<?>> collected = new ArrayList<>();
        WsFrame<?> frame;
        while ((frame = client.handler().frames.poll(waitMs, TimeUnit.MILLISECONDS)) != null) {
            collected.add(frame);
        }
        return collected;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(WsFrame<?> frame) {
        return (Map<String, Object>) frame.getPayload();
    }

    /** 入站帧收集器：文本帧反序列化为协议信封，关闭/错误落 close future */
    private static final class CollectingHandler extends TextWebSocketHandler {

        private final ObjectMapper mapper;
        private final BlockingQueue<WsFrame<?>> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        private CollectingHandler(ObjectMapper mapper) {
            // 帧信封已收口为包私有构造（工厂唯一入口）：从 wire JSON 还原帧需放开
            // creator/field 可见性——copy() 出测试专用 mapper，不动共享的 Spring ObjectMapper
            this.mapper = mapper.copy()
                    .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
            frames.add(mapper.readValue(textMessage.getPayload(), WsFrame.class));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            closed.completeExceptionally(exception);
        }
    }

    private static final class WsClient {

        private final WebSocketSession session;
        private final CollectingHandler handler;

        private WsClient(WebSocketSession session, CollectingHandler handler) {
            this.session = session;
            this.handler = handler;
        }

        public WebSocketSession session() {
            return session;
        }

        public CollectingHandler handler() {
            return handler;
        }

        boolean isOpen() {
            return session.isOpen();
        }

        void sendText(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        CloseStatus awaitClose(long timeoutMs) throws Exception {
            return handler.closed.get(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void closeQuietly() {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {
                // 清理路径失败不影响断言
            }
        }
    }

    // ---------- helpers: chat fixtures ----------

    private String createDirectRoom(Session from, String targetUserId) {
        JsonNode response = postJson("/api/chat/rooms/direct", Map.of("targetUserId", targetUserId), from);
        assertSuccess(response, "建 1v1 会话");
        return response.path("data").path("roomId").asText();
    }

    private JsonNode sendText(String roomId, String clientMsgId, String content, Session sender) {
        return postJson("/api/chat/rooms/messages", Map.of(
                "roomId", roomId, "clientMsgId", clientMsgId, "msgType", "TEXT", "content", content), sender);
    }

    /** 按 destination 取 outbox 事件载荷（id 升序 = 落库顺序）；仅通知等仍走 outbox 的链路使用 */
    private List<String> outboxPayloads(String destination) {
        return jdbcTemplate.queryForList(
                "SELECT payload FROM mq_outbox WHERE destination = ? ORDER BY id", String.class, destination);
    }
}
