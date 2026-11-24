package com.betterschool.integration.support;

import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.trigger.points.listener.LotteryDrawnConsumer;
import com.betterschool.trigger.points.rpc.PointsExchangeDubboService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分域集成测试基类：完整 Spring 上下文，连 scripts/test-env.sh up 备好的常驻栈
 * （better_school_test 库——monolith-restructure 票 07 合库后全应用同一份）。
 *
 * <p>约定（照单体的 {@code PostApiTestBase}）：
 * <ul>
 *   <li>单一 seam：HTTP 走 trigger 入口、MQ 走消费者 Bean（测试栈无 broker，消费侧当普通 Bean 直接调用）、
 *       Dubbo 走 Provider Bean（积分域没有自己的 consumer，真跨进程调用归联调票）</li>
 *   <li>断言只看外部可观察状态：HTTP 响应 + MySQL 落库（points_bucket / points_detail / outbox_message）</li>
 *   <li>不 mock 任何 Bean；broker 不可达的副作用只有日志与 outbox 保持 PENDING</li>
 *   <li>隔离靠库名（better_school_test）与每用例 TRUNCATE</li>
 *   <li>登录态：直接按共享密钥签发 JWT（积分域只验签不签发，没有自己的登录链路）</li>
 * </ul>
 *
 * <p>环境引导在 {@link TestingEnvironment} 里，各域基类共用那一份。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class PointsApiTestBase extends TestingEnvironment {

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    protected TestRestTemplate restTemplate;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected IPointsAccountService pointsAccountService;
    @Autowired
    protected PointsExchangeDubboService pointsExchangeDubboService;
    @Autowired
    protected LotteryDrawnConsumer lotteryDrawnConsumer;

    /** 表隔离：清积分域业务表 + 返利规则（points_validity_config / draw_count_config 是种子单行，不动） */
    @BeforeEach
    void truncateTables() {
        List.of("points_bucket", "points_detail", "action_rebate_config", "outbox_message")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
    }

    // ---------- 登录态 ----------

    /** 按共享密钥签发 USER 令牌（形状与单体 JwtService.generateToken 一致，积分域只验签） */
    protected String tokenOf(String userId) {
        return TestingEnvironment.tokenOf(userId, "USER");
    }

    // ---------- HTTP ----------

    protected ResponseEntity<String> get(String url, String userId) {
        return TestingEnvironment.get(restTemplate, url, userId);
    }

    protected JsonNode getJson(String url, String userId) {
        return TestingEnvironment.getJson(restTemplate, objectMapper, url, userId);
    }

    // ---------- 事件与数据构造 ----------

    /**
     * 投一条 {@code bs-lottery-drawn}：构造 broker 会投递的那份 JSON，走消费者真实的解析与处理路径
     * （测试栈没有 broker，消费者当普通 Bean 调用；真 MQ 链路归联调票）
     */
    protected void publishLotteryDrawn(String userId, String recordId, String prizeType, Integer pointsAmount) {
        // uuid-biz-id 票 07 起 recordId 是 UUID 字符串，载荷里按 JSON 字符串传（原 %d 数字形态退役）
        String payload = "{\"recordId\":\"%s\",\"userId\":\"%s\",\"prizeId\":1,\"prizeType\":\"%s\",\"pointsAmount\":%s}"
                .formatted(recordId, userId, prizeType, pointsAmount == null ? "null" : pointsAmount);
        lotteryDrawnConsumer.onMessage(payload);
    }

    /** 直接造一个历史桶（可指定过期时间），供懒过期与 FIFO 用例铺前置态；返回桶行 id */
    protected long insertBucket(String userId, int amount, int remain, LocalDateTime expireAt, String reasonType,
                                String refId) {
        jdbcTemplate.update("""
                        INSERT INTO points_bucket (bucket_id, user_id, amount, remain, expire_at, reason_type, ref_id,
                                                   create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                com.betterschool.types.id.UuidIdGenerator.INSTANCE.nextId(),
                userId, amount, remain, java.sql.Timestamp.valueOf(expireAt), reasonType, refId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM points_bucket WHERE user_id = ?", Long.class, userId);
    }

    protected int bucketRemainOf(long bucketRowId) {
        Integer remain = jdbcTemplate.queryForObject("SELECT remain FROM points_bucket WHERE id = ?",
                Integer.class, bucketRowId);
        assertThat(remain).as("桶 %s 应还在（过期只置零不删行）", bucketRowId).isNotNull();
        return remain;
    }

    protected int bucketCountOf(String userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM points_bucket WHERE user_id = ?",
                Integer.class, userId);
    }

    protected int detailCountOf(String userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM points_detail WHERE user_id = ?",
                Integer.class, userId);
    }

    /** 铺一条行为返利规则（幂等键 uk = action_type + rebate_type） */
    protected void insertRebateRule(String actionType, String rebateType, int rebateValue, long configId,
                                    String state) {
        jdbcTemplate.update("""
                        INSERT INTO action_rebate_config (action_type, rebate_type, rebate_name, rebate_desc,
                                                          rebate_value, config_id, state, create_time, update_time)
                        VALUES (?, ?, ?, NULL, ?, ?, ?, NOW(), NOW())
                        ON DUPLICATE KEY UPDATE rebate_value = VALUES(rebate_value), state = VALUES(state)
                        """,
                actionType, rebateType, actionType + " 返利", rebateValue, configId, state);
    }

    /** 某 destination 的 outbox 载荷（PENDING + 已发送都算「已发出」，这里没有 broker，落库即可观察） */
    protected List<String> outboxPayloads(String destination) {
        return jdbcTemplate.queryForList("SELECT payload FROM outbox_message WHERE destination = ? ORDER BY id",
                String.class, destination);
    }

    protected JsonNode onlyPointsChangedEvent() {
        List<String> payloads = outboxPayloads("bs-points-changed");
        assertThat(payloads).as("应有且只有一条积分变动事件").hasSize(1);
        return TestingEnvironment.readJson(objectMapper, payloads.get(0));
    }

    /** 直查流水投影（不经过 HTTP，与 selectTransactions 同口径：桶分支排除 COMPACTION），断言 direction / amount / reasonType 用 */
    protected List<Map<String, Object>> transactionsOf(String userId) {
        return jdbcTemplate.queryForList("""
                SELECT direction, amount, reason_type, ref_id
                FROM (
                    SELECT 'IN' AS direction, amount, reason_type, ref_id FROM points_bucket
                    WHERE user_id = ? AND reason_type != 'COMPACTION'
                    UNION ALL
                    SELECT 'OUT' AS direction, amount, reason_type, ref_id FROM points_detail WHERE user_id = ?
                ) merged
                ORDER BY ref_id
                """, userId, userId);
    }
}
