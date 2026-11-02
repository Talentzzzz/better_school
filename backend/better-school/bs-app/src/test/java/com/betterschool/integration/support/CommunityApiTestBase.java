package com.betterschool.integration.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.betterschool.trigger.listener.CommentReviewConsumer;
import com.betterschool.usecase.moderation.ReviewCase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 社区集成测试基类：完整 Spring 上下文，连 scripts/test-env.sh up 备好的常驻测试栈，不自建容器。
 * 全部中间件地址（datasource / redis / rocketmq / minio / dubbo registry / xxl executor）落在
 * application-test.yml——它是测试环境的唯一事实来源；环境没起时测试直接连接失败，先备环境再启动。
 *
 * <p>约定（后续功能测试沿用）：
 * <ul>
 *   <li>单一 seam = trigger 层 HTTP 入口；断言只看外部可观察状态：HTTP 响应 + MySQL/Redis 数据</li>
 *   <li>不 mock 任何内部 Bean；RocketMQ/xxl-job/ES 不可达的副作用仅日志，不影响被测行为</li>
 *   <li>隔离靠常驻栈的多租户机制：MySQL 库 better_school_test、Redis 逻辑库 1、MinIO 桶 bs-files-it；
 *       每个用例前 TRUNCATE 业务表做隔离</li>
 *   <li>登录态走真实链路：send-code → 从 Redis 读验证码 → register 换 JWT</li>
 *   <li>Nacos 是启动硬前置：单体经严格 spring.config.import 取 jwt.secret，故基类把
 *       better-school-shared.yml 写进测试 Nacos（见 {@link #publishSharedConfig()}）</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class CommunityApiTestBase {

    /** 帖子详情缓存键前缀（与 PostDetailCache.DETAIL_KEY 同值；测试只断言缓存外部状态时用） */
    protected static final String DETAIL_CACHE_KEY_PREFIX = "bs:post:detail:";

    /** 测试 Nacos 主端口（compose bs-nacos-test），gRPC 按默认 offset = 主端口 + 1000 = 19848 */
    private static final String TEST_NACOS_ADDR = "127.0.0.1:18848";
    /** 四个 app 共用的共享配置 dataId（jwt.secret / jwt.expire-days） */
    private static final String SHARED_DATA_ID = "better-school-shared.yml";

    @BeforeAll
    static void prepareEnvironment() {
        publishSharedConfig();
    }

    /**
     * 把共享配置写进测试 Nacos：幂等、一次调用、免去手工建配置。spring.cloud.nacos.server-addr
     * 必须走 System.setProperty——spring.config.import 在更早的 config data 阶段解析，
     * profile 文档与 @DynamicPropertySource 在那一阶段都还没挂上；系统属性从一开始就在 Environment 里。
     */
    private static void publishSharedConfig() {
        System.setProperty("spring.cloud.nacos.server-addr", TEST_NACOS_ADDR);
        // tri 端口不能吃 application.yml 的 50051：本机同时开着 IDE 里的单体时会 Address already in use。
        // 与 nacos 地址同路走系统属性，从 Environment 一开始就在取随机空闲端口
        System.setProperty("dubbo.protocol.port", Integer.toString(ephemeralDubboPort()));

        String content = """
                jwt:
                  secret: hb9Sk1afIEG4QUKjSl1LzSOKovLgKLnsYCqaCvN09c2YjRXZBJRh21vGfKAIUNlG
                  expire-days: 7
                """;
        String body = "dataId=" + SHARED_DATA_ID + "&group=DEFAULT_GROUP&type=yaml&content="
                + URLEncoder.encode(content, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + TEST_NACOS_ADDR + "/nacos/v1/cs/configs"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || !"true".equals(response.body().trim())) {
                throw new IllegalStateException("写入 Nacos 共享配置失败：" + response.statusCode() + " " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入 Nacos 共享配置失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("写入 Nacos 共享配置被中断", e);
        }
    }

    private static Integer dubboPort;

    /** 取一个空闲端口并缓存：同 JVM 所有测试类共用一个端口，每次现取会拿到不同端口 */
    private static synchronized int ephemeralDubboPort() {
        if (dubboPort == null) {
            try (ServerSocket socket = new ServerSocket(0)) {
                dubboPort = socket.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException("找不到可用的 Dubbo 端口", e);
            }
        }
        return dubboPort;
    }

    @Autowired
    protected TestRestTemplate restTemplate;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected RedissonClient redissonClient;
    @Autowired
    protected CommentReviewConsumer commentReviewConsumer;
    @Autowired
    protected ReviewCase reviewCase;

    /** 清超时分表用的裸 MySQL 连接（物理分表经 SS 连接查不到，同 TimeoutShardingRouteIntegrationTest） */
    @Value("${timeout-it.raw-mysql-url}")
    private String rawMysqlUrl;
    @Value("${spring.datasource.username}")
    private String rawDbUser;
    @Value("${spring.datasource.password}")
    private String rawDbPassword;

    /** 登录态：token + userId */
    public record Session(String token, String userId) {
    }

    /**
     * 表隔离：清业务表。sensitive_word 例外——词表在应用启动时加载进内存（KeywordScreenServiceImpl），
     * 不做 TRUNCATE，测试直接使用 schema.sql 的种子词（如「作弊」）
     */
    @BeforeEach
    void truncateTables() {
        List.of("bs_user", "post", "post_comment", "post_like", "post_like_count", "post_comment_count",
                        "post_comment_like", "post_comment_like_count", "post_comment_feed_index", "outbox_message",
                        "post_feed_index", "user_view_history", "post_view_count",
                        "product_feed_index", "errand_feed_index",
                        "product_view_history", "errand_view_history",
                        "product_view_count", "errand_view_count",
                        "notification", "virtual_wallet", "wallet_detail", "user_credit_detail",
                        "content_review", "content_report", "review_task",
                        "errand_order", "errand_order_event",
                        "product", "product_favorite", "product_order", "product_order_event",
                        "room", "direct_room_pair", "room_member", "message", "timeline", "user_seq",
                        "notification_seq", "notification_read")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        // 超时分表不在上面的清单里：TRUNCATE 逻辑表经 SS 的路由不可靠（hint 分表无 TRUNCATE 直通），
        // 且**必须**逐用例清——业务表 TRUNCATE 会重置自增 id，残留行会让下一用例的同 biz_id 注册
        // 被三分支幂等吸收（在途不改期），计数/时间窗断言全被陈旧行污染（票 03 踩过）
        truncateTimeoutTaskTables();
        // 帖子 id 每用例重排但 Redis 不随 TRUNCATE 清空：清详情缓存防跨用例脏读
        redissonClient.getKeys().deleteByPattern(DETAIL_CACHE_KEY_PREFIX + "*");
        // 跑腿防占单的计数 ZSET 与冷却标记同理（票 05）：逐用例清，防跨用例脏读
        redissonClient.getKeys().deleteByPattern(Constants.ERRAND_GRAB_ABANDON_KEY + "*");
        redissonClient.getKeys().deleteByPattern(Constants.ERRAND_GRAB_COOLDOWN_KEY + "*");
    }

    /** 裸 MySQL 连接清空 16 张超时分表（测试库专用表，全清无副作用；物理名经 SS 连接查不到） */
    private void truncateTimeoutTaskTables() {
        try (Connection connection = DriverManager.getConnection(rawMysqlUrl, rawDbUser, rawDbPassword);
             Statement statement = connection.createStatement()) {
            for (int g = 0; g < 16; g++) {
                statement.executeUpdate("TRUNCATE TABLE timeout_task_" + String.format("%02d", g));
            }
        } catch (Exception e) {
            throw new IllegalStateException("清空超时分表失败", e);
        }
    }

    // ---------- 登录态 ----------

    /** 注册并返回登录态（手机号加随机后缀防跨用例冲突可省：每用例前已清库） */
    protected Session registerAndLogin(String phone, String nickname) {
        postJson("/api/user/send-code", Map.of("phone", phone), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + phone).get();
        assertThat(code).as("验证码应已写入 Redis").isNotNull();
        JsonNode response = postJson("/api/user/register", Map.of("phone", phone, "code", code, "nickname", nickname), null);
        assertSuccess(response, "注册");
        JsonNode data = response.path("data");
        // uuid-biz-id 票 08 收口：注册返回的 userId 必须是可解析的 UUID 字符串（ID 形态断言）
        assertUuidId(data.path("userInfo").path("userId").asText());
        return new Session(data.path("token").asText(), data.path("userInfo").path("userId").asText());
    }

    /** 统一成功断言：Response.code = 200 */
    protected void assertSuccess(JsonNode response, String what) {
        assertThat(response.path("code").asText()).as("%s应成功：%s", what, response).isEqualTo("200");
    }

    /**
     * 业务 ID 形态断言（uuid-biz-id 票 02~05）：新建资源返回的 id 必须是可解析的 UUID 字符串。
     * fromString 对非法输入直接抛异常——抛出即用例失败，不需要再叠加 isNotNull
     */
    protected void assertUuidId(String id) {
        java.util.UUID.fromString(id);
    }

    // ---------- HTTP 调用 ----------

    /** 带可选登录态发请求，返回原始 ResponseEntity（401/403 场景需断言 HTTP 状态） */
    protected ResponseEntity<String> exchange(HttpMethod method, String url, Object body, Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.setBearerAuth(session.token());
        }
        try {
            return restTemplate.exchange(url, method,
                    new HttpEntity<>(body == null ? null : objectMapper.writeValueAsString(body), headers), String.class);
        } catch (Exception e) {
            throw new IllegalStateException("请求体序列化失败：" + url, e);
        }
    }

    /** 统一响应体解析：HTTP 200 + body JSON（Response{code,info,data}） */
    protected JsonNode callJson(HttpMethod method, String url, Object body, Session session) {
        ResponseEntity<String> response = exchange(method, url, body, session);
        assertThat(response.getStatusCode().value())
                .as("HTTP 应为 200（业务错误走 body.code）：%s %s → %s", method, url, response.getBody())
                .isEqualTo(200);
        return readTree(response.getBody());
    }

    protected JsonNode getJson(String url, Session session) {
        return callJson(HttpMethod.GET, url, null, session);
    }

    protected JsonNode postJson(String url, Object body, Session session) {
        return callJson(HttpMethod.POST, url, body, session);
    }

    protected JsonNode putJson(String url, Object body, Session session) {
        return callJson(HttpMethod.PUT, url, body, session);
    }

    protected JsonNode deleteJson(String url, Session session) {
        return callJson(HttpMethod.DELETE, url, null, session);
    }

    protected JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("响应体解析失败：" + json, e);
        }
    }

    // ---------- 社区用例构造 ----------

    /**
     * 发帖（先审后发：发布落库即待审），并经统一裁决通过——供需要"对外可见帖子"前置态的用例当 fixture；
     * 需要待审/被拒前置态的用例直接走 HTTP 发布后自行裁决。返回 postId
     */
    protected String publishPost(Session author, String content) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "测试帖子", "content", content), author);
        assertSuccess(response, "发帖");
        String postId = response.path("data").asText();
        // uuid-biz-id 票 03：发帖响应 id 必须是可解析的 UUID 字符串（ID 形态断言），且裁决以同一 id 引用成功
        org.assertj.core.api.Assertions.assertThat(java.util.UUID.fromString(postId)).isNotNull();
        reviewCase.decide("POST", postId, true, null, null, author.userId());
        return postId;
    }

    /** 发布评论，返回 HTTP 200 的统一响应体（data = 完整新评论 DTO） */
    protected JsonNode publishComment(String postId, Session author, String content) {
        return postJson("/api/community/posts/" + postId + "/comments", Map.of("content", content), author);
    }

    /** 发布评论并返回新评论 id（响应 data 含完整 DTO） */
    protected String publishCommentAndGetId(String postId, Session author, String content) {
        JsonNode response = publishComment(postId, author, content);
        assertSuccess(response, "发布评论");
        String commentId = response.path("data").path("commentId").asText();
        // uuid-biz-id 票 03：评论响应 id 必须是可解析的 UUID 字符串（ID 形态断言）
        org.assertj.core.api.Assertions.assertThat(java.util.UUID.fromString(commentId)).isNotNull();
        return commentId;
    }

    /**
     * 按类型领单并断言领到的正是目标内容的在途工单（票 08/09 审核流程夹具：裁决前必须先领单——
     * 遗留入口已收口为反查持有人在途工单），返回工单业务键 taskId（UUID 字符串，uuid-biz-id 票 06）
     */
    protected String claimTaskOf(Session auditor, String targetType, String targetId) {
        JsonNode claimed = postJson("/api/moderation/tasks/claim", Map.of("targetType", targetType), auditor);
        assertSuccess(claimed, "领单");
        assertThat(claimed.path("data").path("targetType").asText()).isEqualTo(targetType);
        assertThat(claimed.path("data").path("targetId").asText()).isEqualTo(targetId);
        assertThat(claimed.path("data").path("claimState").asText()).isEqualTo("CLAIMED");
        return claimed.path("data").path("taskId").asText();
    }

    /** 统一裁决 operatorId 哨兵（评论由人工裁决，测试放一把通过/拒绝不校验具体审核员） */
    private static final String SYSTEM_OPERATOR_ID = "system-operator-1";

    /**
     * 直接把一条评论经统一裁决通过（测试快捷：待审→NORMAL）。ADR-0004 后评论无自动审核，
     * 消费方只通知审核员，状态流转一律走统一裁决（ReviewCase）
     */
    protected void driveCommentReview(String commentId) {
        reviewCase.decide("COMMENT", commentId, true, null, null, SYSTEM_OPERATOR_ID);
    }

    /** 直接把一条待审评论经统一裁决拒绝（待审→REJECTED，拒绝理由走全站枚举，落库"枚举文案：补充说明"） */
    protected void driveCommentReject(String commentId, String reasonCode, String supplement) {
        reviewCase.decide("COMMENT", commentId, false, reasonCode, supplement, SYSTEM_OPERATOR_ID);
    }

    /** 全量把当前待审评论经统一裁决通过（用例需要"全部评论已通过"的前置态时用） */
    protected void driveAllCommentReviews() {
        List<String> pendingIds = jdbcTemplate.queryForList(
                "SELECT comment_id FROM post_comment WHERE state = 'PENDING_REVIEW'", String.class);
        pendingIds.forEach(id -> reviewCase.decide("COMMENT", id, true, null, null, SYSTEM_OPERATOR_ID));
    }

    /** 访客视角评论列表：data 节点 = {page, pageSize, total, list} */
    protected JsonNode listCommentsAsGuest(String postId, int page, int pageSize) {
        JsonNode response = getJson("/api/community/posts/" + postId + "/comments?page=" + page + "&pageSize=" + pageSize, null);
        assertSuccess(response, "评论列表");
        return response.path("data");
    }

    // ---------- 数据库可观察状态 ----------

    /** 帖子详情缓存裸桶：断言缓存外部状态用（键前缀与 PostDetailCache.DETAIL_KEY 同值） */
    protected RBucket<String> detailCacheBucket(String postId) {
        return redissonClient.getBucket(DETAIL_CACHE_KEY_PREFIX + postId);
    }

    protected String commentStatusOf(String commentId) {
        return jdbcTemplate.queryForObject("SELECT state FROM post_comment WHERE comment_id = ?", String.class, commentId);
    }

    protected int commentRowCountOf(String postId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM post_comment WHERE post_id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    protected long postCommentCountOf(String postId) {
        // 评论计数已迁到窄表 post_comment_count（sql.md 计数决策），post 行不再存该列；
        // 窄表行是「审核通过 +1」时才 upsert 出来的，尚无行 = 计数 0（与 postLikeCountOf 同口径）
        Long count = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(comment_count), 0) FROM post_comment_count WHERE post_id = ?",
                Long.class, postId);
        return count == null ? 0 : count;
    }

    /** 帖子点赞计数窄表读值（无行 = 0） */
    protected long postLikeCountOf(String postId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(like_count), 0) FROM post_like_count WHERE post_id = ?", Long.class, postId);
        return count == null ? 0 : count;
    }

    /** 列表项 id 序（断言排序/分页用） */
    protected List<String> commentIdsOf(JsonNode listData) {
        return StreamSupport.stream(listData.path("list").spliterator(), false)
                .map(node -> node.path("commentId").asText())
                .toList();
    }

    // ---------- 白盒日志捕获 ----------

    /**
     * 抓指定 logger 的 WARN 及以上日志：合并器<em>落库线程</em>里落的 warn（计数 0 行命中等）在 HTTP 响应
     * 和数据库里都看不见，只能白盒抓日志断言。用 try-with-resources 关闭。
     */
    protected LogCapture captureWarnLogs(Class<?> loggerType) {
        return new LogCapture((Logger) org.slf4j.LoggerFactory.getLogger(loggerType));
    }

    /** 一次日志捕获会话：{@link #warnMessagesContaining} 取本批里命中关键词的 warn 文案 */
    protected static final class LogCapture implements AutoCloseable {

        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private LogCapture(Logger logger) {
            this.logger = logger;
            this.appender.start();
            this.logger.addAppender(this.appender);
        }

        /** 本批 WARN 以上日志里包含任一关键词的文案（关键词把同批次无关模块的 warn 挡在外面） */
        public List<String> warnMessagesContaining(String... keywords) {
            return appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> java.util.Arrays.stream(keywords).anyMatch(message::contains))
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
