package com.betterschool.integration.moderation;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.ws.ReviewSessionRegistry;
import com.betterschool.trigger.ws.ReviewWsFrame;
import com.betterschool.trigger.ws.ReviewWsFrameType;
import com.betterschool.trigger.ws.ReviewWsProtocol;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.websocket.DeploymentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * review WS 连接层 + presence 全链路行为集成测试（落地票 10，spec §5.3「presence」「推送通道」）：
 * 真 WS 客户端直连 RANDOM_PORT。本票只交付「连得上、在线可见、断得掉」——
 * REVIEW_SIGNAL 的内容与推送时机归票 11。
 *
 * <p>覆盖：审核角色连得上（PING/PONG）、角色过滤（USER 的 ws-ticket 403 + 拿 chat 票据跨端点握手 401）、
 * 票据一次性、presence 三写点（建立写入/心跳刷新/断开删除）、单设备互踢 close(4001) 且
 * 被踢旧连接迟到 close 不误删在线记录、60s 无入站判死清理（presence 一并删除）。
 * 审核端点未被 chat 端点复用、chat 语义未被改动由 ChatWsIntegrationTest 全绿背书。
 */
public class ReviewWsIntegrationTest extends CommunityApiTestBase {

    @LocalServerPort
    private int port;

    @Resource
    private ReviewSessionRegistry reviewSessionRegistry;

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

    // ---------- 1. 角色过滤与票据 ----------

    @Test
    void wsTicketRequiresAuditorRole() {
        Session user = registerAndLogin("13800005001", "普通用户");
        JsonNode response = postJson("/api/moderation/ws-ticket", Map.of(), user);
        assertThat(response.path("code").asText())
                .as("非审核员/管理员签发票据应被拒（403）")
                .isEqualTo("403");
    }

    @Test
    void handshakeRejectsInvalidExpiredMissingTicketAndNonAuditorIdentity() throws Exception {
        Session auditor = auditorSession("13800005002", "审核员");

        // 未知票据
        assertHandshakeRejected(wsUrl("no-such-ticket"));

        // 过期票据：TTL 60s 由 Redis 承担，测试删除 key 模拟过期（不真等 60s）
        String expired = issueTicket(auditor);
        redissonClient.getBucket(Constants.WS_TICKET_KEY + expired).delete();
        assertHandshakeRejected(wsUrl(expired));

        // 无票据
        assertHandshakeRejected("ws://localhost:" + port + ReviewWsProtocol.ENDPOINT);

        // 身份不合规：普通用户拿 chat 端点签发的票据跨端点握手——票据与端点无关，
        // 审核端点在握手时点回查角色把关（票据签发口的 403 挡不住既有 chat 票据）
        Session plainUser = registerAndLogin("13800005003", "普通用户B");
        JsonNode chatTicketResp = postJson("/api/chat/ws-ticket", Map.of(), plainUser);
        assertSuccess(chatTicketResp, "普通用户签发 chat 票据（chat 无角色门槛）");
        assertHandshakeRejected(wsUrl(chatTicketResp.path("data").path("ticket").asText()));
    }

    @Test
    void handshakeTicketIsSingleUse() throws Exception {
        Session auditor = auditorSession("13800005004", "审核员");
        String ticket = issueTicket(auditor);

        WsClient connection = connectWithTicket(ticket);
        assertThat(connection.isOpen()).isTrue();

        // 原子取删：同一票据第二次握手必被拒（重放防护）
        assertHandshakeRejected(wsUrl(ticket));
        assertThat(connection.isOpen()).as("重放失败不影响已建立的连接").isTrue();
    }

    // ---------- 2. 连得上 + presence ----------

    @Test
    void auditorConnectsAndPresenceWrittenThenRefreshedByHeartbeat() throws Exception {
        Session auditor = auditorSession("13800005011", "审核员");

        WsClient connection = connect(auditor);
        connection.sendText("{\"type\":\"PING\"}");
        assertThat(awaitFrame(connection, ReviewWsFrameType.PONG, 5_000))
                .as("审核员连得上：PING 应得 PONG 应答").isNotNull();

        // presence：连接建立写入（值 = 最近活跃毫秒）
        long tsAfterConnect = presenceOf(auditor.userId());
        assertThat(tsAfterConnect).as("连接建立后在线集合应含该审核员").isPositive();

        // 心跳刷新：任意入站帧刷新最近活跃时刻（隔一小段时间发 PING，值应前移）
        Thread.sleep(30);
        connection.sendText("{\"type\":\"PING\"}");
        assertThat(awaitFrame(connection, ReviewWsFrameType.PONG, 5_000)).isNotNull();
        assertThat(presenceOf(auditor.userId()))
                .as("入站帧应刷新在线记录的活跃时刻")
                .isGreaterThan(tsAfterConnect);

        // C→S 仅 PING：其余类型无上行路由，静默忽略不断连
        connection.sendText("{\"type\":\"REVIEW_SIGNAL\"}");
        assertThat(connection.isOpen()).as("非 PING 上行被忽略，连接不断").isTrue();
    }

    @Test
    void disconnectRemovesPresenceEntry() throws Exception {
        Session auditor = auditorSession("13800005012", "审核员");

        connect(auditor).closeQuietly();
        // 等服务端 close 回调走完（presence 删除在 afterConnectionClosed）
        awaitPresenceRemoved(auditor.userId());
        assertThat(presenceOf(auditor.userId()))
                .as("断开后在线集合应清理该审核员").isNull();
    }

    // ---------- 3. 互踢 ----------

    @Test
    void secondConnectionKicksFirstAndPresenceStaysOnline() throws Exception {
        Session auditor = auditorSession("13800005021", "审核员");

        WsClient first = connect(auditor);
        WsClient second = connect(auditor);

        // 旧连接先收 KICK（reason 供客户端提示重新登录），随后被 close(4001) 阻断自动重连
        ReviewWsFrame kick = awaitFrame(first, ReviewWsFrameType.KICK, 5_000);
        assertThat(kick).as("旧连接应收到 KICK").isNotNull();
        assertThat(payloadOf(kick)).containsEntry("reason", ReviewWsProtocol.KICK_REASON_SESSION_TAKEN_OVER);
        assertThat(first.awaitClose(5_000).getCode()).as("互踢关闭码 4001").isEqualTo(ReviewWsProtocol.CLOSE_CODE_TAKEN_OVER);
        assertThat(second.isOpen()).as("新连接保持在线").isTrue();

        // presence：被踢旧连接的迟到 close 不误删在线记录（新连接仍是当前连接）
        assertThat(presenceOf(auditor.userId()))
                .as("互踢后在线记录应保持（归新连接）").isPositive();

        // 新连接仍可用：心跳应答正常
        second.sendText("{\"type\":\"PING\"}");
        assertThat(awaitFrame(second, ReviewWsFrameType.PONG, 5_000)).as("新连接心跳正常").isNotNull();
    }

    // ---------- 4. 判死清理 ----------

    @Test
    void silentSessionSweptAsDeadAndPresenceRemoved() throws Exception {
        Session auditor = auditorSession("13800005031", "审核员");
        WsClient connection = connect(auditor);
        assertThat(presenceOf(auditor.userId())).isPositive();

        // 正向：刚入站（建连 PING 刷新过活跃时间）的会话不被清扫误杀
        reviewSessionRegistry.sweep(System.currentTimeMillis(), ReviewSessionRegistry.DEAD_THRESHOLD_MS);
        assertThat(connection.isOpen()).as("入站刷新过的会话不判死").isTrue();

        // 判死=阈值内无入站：注入未来时钟驱动清扫，等价 60s 静默（真实等待不做）
        reviewSessionRegistry.sweep(System.currentTimeMillis() + ReviewSessionRegistry.DEAD_THRESHOLD_MS + 1_000,
                ReviewSessionRegistry.DEAD_THRESHOLD_MS);

        CloseStatus status = connection.awaitClose(5_000);
        assertThat(status.getCode()).as("判死清理用 GOING_AWAY").isEqualTo(CloseStatus.GOING_AWAY.getCode());
        awaitPresenceRemoved(auditor.userId());
        assertThat(presenceOf(auditor.userId()))
                .as("判死清理后在线集合应删除该审核员").isNull();
    }

    // ---------- helpers: WS ----------

    private String wsUrl(String ticket) {
        return "ws://localhost:" + port + ReviewWsProtocol.ENDPOINT + "?ticket=" + ticket;
    }

    private String issueTicket(Session auditor) {
        JsonNode response = postJson("/api/moderation/ws-ticket", Map.of(), auditor);
        assertSuccess(response, "签发审核票据");
        return response.path("data").path("ticket").asText();
    }

    private WsClient connect(Session auditor) throws Exception {
        return connectWithTicket(issueTicket(auditor));
    }

    /**
     * 建连 + PING/PONG 同步：握手 future 完成早于服务端 afterConnectionEstablished（注册表/presence 写入），
     * 同步一次往返确保后续断言时序稳定
     */
    private WsClient connectWithTicket(String ticket) throws Exception {
        CollectingHandler handler = new CollectingHandler(objectMapper);
        WebSocketSession session = wsClient.execute(handler, wsUrl(ticket)).get(10, TimeUnit.SECONDS);
        WsClient client = new WsClient(session, handler);
        openClients.add(client);
        client.sendText("{\"type\":\"PING\"}");
        assertThat(awaitFrame(client, ReviewWsFrameType.PONG, 5_000)).as("建连同步 PONG").isNotNull();
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

    private ReviewWsFrame awaitFrame(WsClient client, ReviewWsFrameType type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            ReviewWsFrame frame = client.handler().frames.poll(remaining, TimeUnit.MILLISECONDS);
            if (frame == null) {
                return null;
            }
            if (frame.getType() == type) {
                return frame;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(ReviewWsFrame frame) {
        return (Map<String, Object>) frame.getPayload();
    }

    /** 入站帧收集器：文本帧反序列化为协议信封，关闭/错误落 close future */
    private static final class CollectingHandler extends TextWebSocketHandler {

        private final ObjectMapper mapper;
        private final BlockingQueue<ReviewWsFrame> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        private CollectingHandler(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            frames.add(mapper.readValue(message.getPayload(), ReviewWsFrame.class));
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

    private record WsClient(WebSocketSession session, CollectingHandler handler) {

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

    // ---------- helpers: presence / 角色 ----------

    @SuppressWarnings("unchecked")
    private Long presenceOf(String userId) {
        RMap<Long, Long> map = redissonClient.getMap(Constants.REVIEW_PRESENCE_KEY);
        return map.get(userId);
    }

    /** 等服务端 close 回调处理完（presence 删除是 close 事件里的动作，稍晚于客户端 close 返回） */
    private void awaitPresenceRemoved(String userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (presenceOf(userId) == null) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /** 注册并提升为审核员，重新登录换带 AUDITOR 角色的新 JWT（role 是签发快照，改库后必须重登） */
    private Session auditorSession(String phone, String nickname) {
        Session registered = registerAndLogin(phone, nickname);
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", phone), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + phone).get();
        assertThat(code).as("验证码应已写入 Redis").isNotNull();
        JsonNode response = postJson("/api/user/login", Map.of("phone", phone, "code", code), null);
        assertSuccess(response, "审核员重新登录");
        JsonNode data = response.path("data");
        return new Session(data.path("token").asText(), data.path("userInfo").path("userId").asText());
    }
}
