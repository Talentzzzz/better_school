package com.betterschool.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个域测试基类共用的**环境引导 + HTTP/JSON 收发 + JWT 签发**（票 07 抽出来的唯一一份）。
 * <p>
 * 为什么抽出来：票 06 建 points 基类、票 07 并入 lottery 时，后一个基类把这段「Nacos 地址注入 →
 * 发布共享配置 → 取空闲 Dubbo 端口 → 按共享密钥签 JWT → TestRestTemplate 收发 → 解信封」整段各抄了一遍
 * （两份逐字重复）。域基类之间**不能互相 extends**（清表集与 `@SpringBootTest` 上下文不同），
 * 但它们共用的这段与域无关，故落在一个不走 Spring、不继承的无状态工具类里，由各基类各自委托。
 * 本类的抽取是**纯搬运**：方法名、签名、断言文案、`System.setProperty` 的键与值一字未改。
 */
public class TestingEnvironment {

    /** 测试 Nacos 主端口（compose bs-nacos-test），gRPC 按默认 offset = 主端口 + 1000 = 19848 */
    private static final String TEST_NACOS_ADDR = "127.0.0.1:18848";
    /** 四个 app 共用的共享配置 dataId（jwt.secret / jwt.expire-days） */
    private static final String SHARED_DATA_ID = "better-school-shared.yml";
    /** 与单体测试基类同一串测试密钥：签发的令牌四个 app 都验得过 */
    private static final String JWT_SECRET =
            "hb9Sk1afIEG4QUKjSl1LzSOKovLgKLnsYCqaCvN09c2YjRXZBJRh21vGfKAIUNlG";

    private static Integer dubboPort;
    private static boolean environmentReady;

    /** 两个域基类 extends 本类以复用 HTTP/JSON/JWT 助手；本类自身不注册为测试类 */
    protected TestingEnvironment() {
    }

    /**
     * 把测试环境的进程级前置做掉（幂等，同 JVM 只真跑一次）：
     * {@code spring.cloud.nacos.server-addr} 与 {@code dubbo.protocol.port} 走 {@code System.setProperty}
     * ——{@code spring.config.import} 在更早的 config-data 阶段解析，profile 文档与
     * {@code @DynamicPropertySource} 那时都还没挂上；再往测试 Nacos 写一次共享配置。
     * <p>
     * 两个域基类的 {@code @BeforeAll} 各自调它：先到的那个干活，后到的直接返回。
     * （票 10 起各域测试类也直接调：可见性从包私有放宽为 public，纯搬运语义不变。）
     */
    public static synchronized void ensureEnvironment() {
        if (environmentReady) {
            return;
        }
        System.setProperty("spring.cloud.nacos.server-addr", TEST_NACOS_ADDR);
        // tri 端口不能吃 application.yml 的 50052：本机同时开着 IDE 里的服务时会 Address already in use
        System.setProperty("dubbo.protocol.port", Integer.toString(ephemeralDubboPort()));
        publishSharedConfig();
        environmentReady = true;
    }

    private static void publishSharedConfig() {
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

    // ---------- 登录态 ----------

    /** 按共享密钥签发令牌（形状与单体 JwtService.generateToken 一致，营销服务只验签不签发） */
    public static String tokenOf(String userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000L))
                .signWith(key)
                .compact();
    }

    // ---------- HTTP ----------

    /** 统一响应体解析：HTTP 200 + body JSON（Response{code,info,data}） */
    public static JsonNode getJson(TestRestTemplate restTemplate, ObjectMapper objectMapper, String url, String userId) {
        ResponseEntity<String> response = get(restTemplate, url, userId);
        assertThat(response.getStatusCode().value())
                .as("HTTP 应为 200：GET %s → %s", url, response.getBody())
                .isEqualTo(200);
        return readJson(objectMapper, response.getBody());
    }

    public static ResponseEntity<String> get(TestRestTemplate restTemplate, String url, String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.setBearerAuth(tokenOf(userId, "USER"));
        }
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    /** 已签发的 bearer token 直接进 header（lottery 域要用 ADMIN 角色打第二条路径） */
    public static ResponseEntity<String> post(TestRestTemplate restTemplate, String url, String bearerToken, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);
    }

    public static JsonNode readJson(ObjectMapper objectMapper, String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应体解析失败：" + body, e);
        }
    }

    // ---------- 断言 ----------

    public static void assertSuccess(JsonNode response, String what) {
        assertThat(response.path("code").asText()).as("%s应成功：%s", what, response).isEqualTo("200");
    }

    /** 断言业务失败：返回固定业务码，info 是逐条固定的中文文案 */
    public static void assertFailure(JsonNode response, String expectedCode, String expectedInfo) {
        assertThat(response.path("code").asText()).as("业务码：%s", response).isEqualTo(expectedCode);
        assertThat(response.path("info").asText()).as("失败文案（逐条固定）：%s", response).isEqualTo(expectedInfo);
    }
}
