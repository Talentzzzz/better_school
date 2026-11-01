package com.betterschool.integration.moderation;

import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.ReviewDispatchObservationJob;
import com.betterschool.trigger.ws.ReviewWsFrame;
import com.betterschool.trigger.ws.ReviewWsFrameType;
import com.betterschool.trigger.ws.ReviewWsProtocol;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派单信号推送（票 11，spec §5.3「信号粒度」「离线积累」「降级」+ §9 可观测）全链路集成测试：
 * 真 WS 客户端直连 RANDOM_PORT，seam = 入池 HTTP 动作（发帖/支付） + MySQL/Redis 可观察状态。
 *
 * <p>覆盖：入池 → 在线审核员收到 REVIEW_SIGNAL（积压数与池内行数一致、连续入池逐次一致）；
 * 审核员离线转在线立即补推当前积压数（离线期间不推、不丢单）；无人在线静默积压；
 * 推送广播不指定收件人、不做分配、不引入锁（工单仍停 PENDING、claim_by 为空）；
 * 可观测计数（领单 +1、租约超时回收 +1、主动放回不计）与观测 job 冒烟。
 */
public class ReviewDispatchSignalIntegrationTest extends CommunityApiTestBase {

    @LocalServerPort
    private int port;

    @Resource
    private IReviewService reviewService;

    @Resource
    private ReviewDispatchObservationJob observationJob;

    private StandardWebSocketClient wsClient;

    private final List<WsClient> openClients = new ArrayList<>();

    @BeforeEach
    void initWsClient() {
        wsClient = new StandardWebSocketClient();
        openClients.clear();
        // 观测计数 key 不随 TRUNCATE 清（Redis）：逐用例清，防跨用例脏读
        redissonClient.getKeys().delete(Constants.REVIEW_METRIC_CLAIM_COUNT_KEY,
                Constants.REVIEW_METRIC_LEASE_TIMEOUT_KEY);
    }

    @AfterEach
    void closeWsClients() {
        openClients.forEach(WsClient::closeQuietly);
        openClients.clear();
    }

    // ---------- 1. 上线补推 ----------

    @Test
    void 审核员上线立即补推当前积压数且与池内行数一致() {
        Session author = registerAndLogin("13800011001", "作者");
        // 离线期间入池两单：不推（无连接可推）、不丢单——池是 DB 持久化的
        createPaidOrder(author);
        createPaidOrder(author);
        assertThat(pendingTaskCount()).isEqualTo(2);

        // 审核员连接（= 由离线转在线）：建连即收到补推信号，积压数与池内 PENDING 行数一致
        Session auditor = auditorSession("13800011002", "审核员");
        WsClient connection = connectAuditor(auditor);
        ReviewWsFrame backfill = awaitSignal(connection);
        assertThat(backfill).as("上线应立即补推派单信号").isNotNull();
        assertThat(backlogOf(backfill))
                .as("补推积压数应与池内行数一致")
                .isEqualTo(pendingTaskCount())
                .isEqualTo(2L);
    }

    // ---------- 2. 入池广播 ----------

    @Test
    void 新工单入池在线审核员收到信号_连续多次入池积压数与池内行数一致() {
        Session author = registerAndLogin("13800011003", "作者");
        Session auditor = auditorSession("13800011004", "审核员");
        WsClient connection = connectAuditor(auditor);

        // 建连补推信号（空池 → backlog 0），先消费掉再验证入池广播
        ReviewWsFrame backfill = awaitSignal(connection);
        assertThat(backlogOf(backfill)).as("空池上线补推积压数应为 0").isZero();

        // 逐次入池：每次广播的积压数都与池内 PENDING 行数一致
        createPaidOrder(author);
        assertThat(backlogOf(awaitSignal(connection)))
                .as("第 1 单入池信号积压数").isEqualTo(pendingTaskCount()).isEqualTo(1L);

        // 连续（并发面）多次入池：信号逐条广播，最后一条的积压数 = 池内行数
        createPaidOrder(author);
        createPaidOrder(author);
        assertThat(backlogOf(awaitSignal(connection))).isEqualTo(2L);
        assertThat(backlogOf(awaitSignal(connection)))
                .as("第 3 单入池信号积压数应与池内行数一致")
                .isEqualTo(pendingTaskCount())
                .isEqualTo(3L);
    }

    // ---------- 3. 广播不指定收件人、不锁 ----------

    @Test
    void 信号广播给全部在线审核员且不指定收件人不锁工单() {
        Session author = registerAndLogin("13800011005", "作者");
        Session auditorA = auditorSession("13800011006", "审核员A");
        Session auditorB = auditorSession("13800011007", "审核员B");
        WsClient connectionA = connectAuditor(auditorA);
        WsClient connectionB = connectAuditor(auditorB);
        awaitSignal(connectionA);
        awaitSignal(connectionB);

        // 一单入池：两个在线审核员都收到同一条粗粒度广播（不指定给谁）
        createPaidOrder(author);
        long backlogA = backlogOf(awaitSignal(connectionA));
        long backlogB = backlogOf(awaitSignal(connectionB));
        assertThat(backlogA).as("两名在线审核员应收到同一积压数").isEqualTo(backlogB).isEqualTo(1L);

        // 推送不锁任何东西：广播后工单仍停 PENDING、无人被指定（claim_by 为空）
        assertThat(pendingTaskCount()).isEqualTo(1);
        Long claimedBy = jdbcTemplate.queryForObject(
                "SELECT claim_by FROM review_task WHERE claim_state = 'PENDING'", Long.class);
        assertThat(claimedBy).as("派单信号不分配工单，claim_by 应为空").isNull();
    }

    // ---------- 4. 无人在线静默积压 ----------

    @Test
    void 无人在线静默积压不丢单且计数与池一致() {
        Session author = registerAndLogin("13800011008", "作者");
        createPaidOrder(author);

        // 无任何 WS 连接：不推（无端可证）、不丢单——工单在池、计数端点与池内行数一致
        assertThat(pendingTaskCount()).as("无人在线工单仍应在池").isEqualTo(1);
        Session admin = adminSession();
        JsonNode counts = getJson("/api/moderation/tasks/counts", admin);
        assertSuccess(counts, "积压计数");
        assertThat(counts.path("data").path("ERRAND").asLong()).isEqualTo(1);
    }

    // ---------- 5. 可观测计数 + 观测 job 冒烟 ----------

    @Test
    void 观测计数_领单与租约超时回收_主动放回不计() {
        Session author = registerAndLogin("13800011009", "作者");
        Session auditor = auditorSession("13800011010", "审核员");

        String orderId1 = createPaidOrder(author);
        long taskId1 = claimTaskOf(auditor, "ERRAND", orderId1);
        assertThat(claimCounter()).as("领单成功应计 1 次").isEqualTo(1);
        assertThat(timeoutCounter()).as("未回收时租约超时计数为 0").isZero();

        // 租约超时回收（轮次守卫路径，同超时中心回调口径）：计入租约超时
        int claimSeq1 = claimSeqOf(taskId1);
        reviewService.handleLeaseTimeout(taskId1, claimSeq1);
        assertThat(timeoutCounter()).as("租约超时回收应计 1 次").isEqualTo(1);

        // 主动放回：recycle_count 虽 +1，但那是审核员主动行为，不算租约超时。
        // 领单是全局 FIFO（被回收的工单1 entered_at 更早仍排最前），此处不断言领到哪单、只领一件
        createPaidOrder(author);
        JsonNode claimed = postJson("/api/moderation/tasks/claim", Map.of("targetType", "ERRAND"), auditor);
        assertSuccess(claimed, "第二次领单");
        long claimedTaskId = claimed.path("data").path("taskId").asLong();
        assertThat(claimed.path("data").path("claimState").asText()).isEqualTo("CLAIMED");
        assertThat(claimCounter()).as("第二次领单累计 2 次").isEqualTo(2);
        assertSuccess(postJson("/api/moderation/tasks/" + claimedTaskId + "/release",
                Map.of("reason", "审不了"), auditor), "主动放回");
        assertThat(timeoutCounter()).as("主动放回不应计入租约超时").isEqualTo(1);

        // 等待统计（可观测口径 ③）：池内待审行 = 租约超时回收的任务1 + 主动放回的任务2；
        // 等待时长 = DB NOW() - entered_at（JVM 时刻写入），两钟差 ~1s 会让刚入池的行落微负值，容差 -5s
        var waitStats = reviewService.pendingWaitStats();
        assertThat(waitStats.pendingCount()).as("待审行数（回收 + 放回各 1 条）").isEqualTo(2);
        assertThat(waitStats.avgWaitSeconds()).isGreaterThanOrEqualTo(-5);
        assertThat(waitStats.oldestWaitSeconds()).isGreaterThanOrEqualTo(-5);

        // 观测 job 冒烟：四项口径 + 两条告警评估可执行、不抛错（阈值 50/30 不触发告警）
        String result = observationJob.observe("test");
        assertThat(result).contains("backlog=2");
    }

    // ---------- helpers: WS（与 ReviewWsIntegrationTest 同款客户端骨架） ----------

    private String wsUrl(String ticket) {
        return "ws://localhost:" + port + ReviewWsProtocol.ENDPOINT + "?ticket=" + ticket;
    }

    private String issueTicket(Session auditor) {
        JsonNode response = postJson("/api/moderation/ws-ticket", Map.of(), auditor);
        assertSuccess(response, "签发审核票据");
        return response.path("data").path("ticket").asText();
    }

    /**
     * 建连 + PING/PONG 同步：握手 future 完成早于服务端 afterConnectionEstablished
     * （注册表/presence/补推），同步一次往返确保后续断言时序稳定；
     * 建连补推信号由用例自行 awaitSignal 消费
     */
    private WsClient connectAuditor(Session auditor) {
        try {
            CollectingHandler handler = new CollectingHandler(objectMapper);
            WebSocketSession session = wsClient.execute(handler, wsUrl(issueTicket(auditor))).get(10, TimeUnit.SECONDS);
            WsClient client = new WsClient(session, handler);
            openClients.add(client);
            client.sendText("{\"type\":\"PING\"}");
            assertThat(awaitFrame(client, ReviewWsFrameType.PONG, 5_000)).as("建连同步 PONG").isNotNull();
            return client;
        } catch (Exception e) {
            throw new IllegalStateException("WS 建连失败", e);
        }
    }

    private ReviewWsFrame awaitSignal(WsClient client) {
        try {
            return awaitFrame(client, ReviewWsFrameType.REVIEW_SIGNAL, 5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 REVIEW_SIGNAL 被中断", e);
        }
    }

    private ReviewWsFrame awaitFrame(WsClient client, ReviewWsFrameType type, long timeoutMs)
            throws InterruptedException {
        // 帧按类型分队（见 CollectingHandler）：直接等本类型队列，不混队轮询、不丢其他类帧
        return client.handler().queueOf(type).poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 派单信号载荷：粗粒度总信号 {backlog: N}（不按类型分摊） */
    @SuppressWarnings("unchecked")
    private long backlogOf(ReviewWsFrame frame) {
        assertThat(frame).isNotNull();
        Map<String, Object> payload = (Map<String, Object>) frame.getPayload();
        return ((Number) payload.get("backlog")).longValue();
    }

    /** 入站帧收集器：文本帧反序列化为协议信封后**按帧类型分队**——等待某类帧不会丢掉其他类帧
     * （建连补推信号与 PONG 同期到达，混队轮询会在等 PONG 时把信号丢掉）；关闭/错误落 close future */
    private static final class CollectingHandler extends TextWebSocketHandler {

        private final ObjectMapper mapper;
        private final Map<ReviewWsFrameType, BlockingQueue<ReviewWsFrame>> framesByType = new ConcurrentHashMap<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        private CollectingHandler(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                ReviewWsFrame frame = mapper.readValue(message.getPayload(), ReviewWsFrame.class);
                framesByType.computeIfAbsent(frame.getType(), k -> new LinkedBlockingQueue<>()).add(frame);
            } catch (Exception ignored) {
                // 非协议帧直接丢弃
            }
        }

        BlockingQueue<ReviewWsFrame> queueOf(ReviewWsFrameType type) {
            return framesByType.computeIfAbsent(type, k -> new LinkedBlockingQueue<>());
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

        void sendText(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
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

    // ---------- helpers: 业务夹具 / 可观察状态 ----------

    /** 发布并支付跑腿订单（入池点：支付成功同事务开单），返回 orderId */
    private String createPaidOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "帮忙取个快递");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", 10);
        body.put("timeLimit", "HOURS_2");
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    /** 池内 PENDING 行数（积压数的事实源） */
    private long pendingTaskCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM review_task WHERE claim_state = 'PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    private int claimSeqOf(long taskId) {
        Integer seq = jdbcTemplate.queryForObject(
                "SELECT claim_seq FROM review_task WHERE id = ?", Integer.class, taskId);
        return seq == null ? -1 : seq;
    }

    private long claimCounter() {
        Long count = redissonClient.getAtomicLong(Constants.REVIEW_METRIC_CLAIM_COUNT_KEY).get();
        return count == null ? 0 : count;
    }

    private long timeoutCounter() {
        Long count = redissonClient.getAtomicLong(Constants.REVIEW_METRIC_LEASE_TIMEOUT_KEY).get();
        return count == null ? 0 : count;
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

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900011001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900011001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900011001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900011001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }
}
