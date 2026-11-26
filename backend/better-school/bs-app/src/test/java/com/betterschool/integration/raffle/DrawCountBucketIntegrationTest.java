package com.betterschool.integration.raffle;

import com.betterschool.domain.raffle.service.drawcount.IDrawCountAccountService;
import com.betterschool.integration.support.TestingEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽奖次数桶集成用例：入账落桶、FIFO 扣次、过期清理、不足拒绝——与积分侧同构的验收面。
 * 抽奖次数与抽奖**同域**（{@code domain.raffle.service.drawcount}），真实调用方是抽奖主链路
 * 与「用积分购买次数」，这里走域服务 seam 直测账务本身。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("抽奖次数 · 桶模型")
class DrawCountBucketIntegrationTest extends TestingEnvironment {

    private static final String USER_ID = "user-1005";
    private static final String REDEEM_ORDER_ID = "95001";

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
    protected IDrawCountAccountService drawCountAccountService;

    /** 表隔离：清次数桶业务表 + 返利规则（draw_count_config 是种子单行，不动） */
    @BeforeEach
    void truncateTables() {
        List.of("draw_count_bucket", "draw_count_detail", "action_rebate_config", "mq_outbox")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
    }

    /** 直接造一个历史次数桶（可指定过期时间），返回桶行 id */
    private long insertDrawCountBucket(String userId, int amount, int remain, LocalDateTime expireAt, String reasonType,
                                       String refId) {
        jdbcTemplate.update("""
                        INSERT INTO draw_count_bucket (bucket_id, user_id, amount, remain, expire_at, reason_type,
                                                       ref_id, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                com.betterschool.types.id.UuidIdGenerator.INSTANCE.nextId(),
                userId, amount, remain, java.sql.Timestamp.valueOf(expireAt), reasonType, refId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM draw_count_bucket WHERE user_id = ?",
                Long.class, userId);
    }

    private int bucketRemainOf(long bucketRowId) {
        Integer remain = jdbcTemplate.queryForObject("SELECT remain FROM draw_count_bucket WHERE id = ?",
                Integer.class, bucketRowId);
        assertThat(remain).isNotNull();
        return remain;
    }

    private int bucketCountOf(String userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_count_bucket WHERE user_id = ?",
                Integer.class, userId);
    }

    @Test
    @DisplayName("兑换入账：落 REDEEM 桶（有效期 = draw_count_valid_days 30 天），重试幂等")
    void creditForPurchase_createsBucketAndIsIdempotent() {
        assertThat(drawCountAccountService.creditForPurchase(USER_ID, REDEEM_ORDER_ID, 3)).isTrue();
        assertThat(drawCountAccountService.creditForPurchase(USER_ID, REDEEM_ORDER_ID, 3))
                .as("同一兑换单重试不重复加次数").isTrue();

        assertThat(bucketCountOf(USER_ID)).isEqualTo(1);
        var bucket = jdbcTemplate.queryForMap(
                "SELECT bucket_id, amount, remain, reason_type, ref_id, expire_at FROM draw_count_bucket WHERE user_id = ?",
                USER_ID);
        assertThat(bucket.get("amount")).isEqualTo(3);
        assertThat(bucket.get("remain")).isEqualTo(3);
        assertThat(bucket.get("reason_type")).isEqualTo("PURCHASE");
        assertThat(String.valueOf(bucket.get("ref_id"))).isEqualTo(String.valueOf(REDEEM_ORDER_ID));
        assertThat((LocalDateTime) bucket.get("expire_at"))
                .as("过期时间 = 入账时点 + draw_count_valid_days(30)")
                .isAfter(LocalDateTime.now().plusDays(29));
        assertThat(drawCountAccountService.getBalance(USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("扣次 FIFO：先扣最早过期的桶，扣完落到下一个；明细指向桶业务 ID")
    void deductForDraw_followsFifoAcrossBuckets() {
        long firstBucketId = insertDrawCountBucket(USER_ID, 1, 1, LocalDateTime.now().plusDays(1), "PURCHASE", "96001");
        long secondBucketId = insertDrawCountBucket(USER_ID, 2, 2, LocalDateTime.now().plusDays(10), "PURCHASE", "96002");

        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97001")).isTrue();
        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97002")).isTrue();

        assertThat(bucketRemainOf(firstBucketId)).as("先过期的那桶被扣光").isZero();
        assertThat(bucketRemainOf(secondBucketId)).as("下一抽落到后过期的桶").isEqualTo(1);
        assertThat(drawCountAccountService.getBalance(USER_ID)).isEqualTo(1);

        List<String> detailBucketIds = jdbcTemplate.queryForList(
                "SELECT bucket_id FROM draw_count_detail WHERE ref_id IN ('97001', '97002') ORDER BY ref_id",
                String.class);
        assertThat(detailBucketIds).hasSize(2);
        List<String> bucketBusinessIds = jdbcTemplate.queryForList(
                "SELECT bucket_id FROM draw_count_bucket WHERE user_id = ?", String.class, USER_ID);
        assertThat(bucketBusinessIds).as("明细引用的桶业务 ID 必须在 draw_count_bucket 里")
                .containsAll(detailBucketIds);
    }

    @Test
    @DisplayName("同一抽奖记录重放：幂等返回 true，不重复扣次")
    void deductForDraw_sameRecordId_isIdempotent() {
        insertDrawCountBucket(USER_ID, 2, 2, LocalDateTime.now().plusDays(5), "PURCHASE", "96003");

        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97003")).isTrue();
        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97003"))
                .as("同一 record_id 重放按已扣成功返回").isTrue();

        assertThat(drawCountAccountService.getBalance(USER_ID)).isEqualTo(1);
        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_detail WHERE ref_id = '97003'", Integer.class);
        assertThat(detailRows).isEqualTo(1);
    }

    @Test
    @DisplayName("次数不足：拒绝扣次且不产生半扣（无明细、余量不变）")
    void deductForDraw_insufficient_changesNothing() {
        long bucketId = insertDrawCountBucket(USER_ID, 0, 0, LocalDateTime.now().plusDays(5), "PURCHASE", "96004");

        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97004")).isFalse();

        assertThat(bucketRemainOf(bucketId)).isZero();
        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_detail WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(detailRows).isZero();

        // 扣完全部可用次数后再抽同样拒绝
        insertDrawCountBucket(USER_ID, 1, 1, LocalDateTime.now().plusDays(5), "PURCHASE", "96005");
        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97005")).isTrue();
        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97006")).isFalse();
        Integer detailRowsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_detail WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(detailRowsAfter).as("拒绝的那一抽不留明细").isEqualTo(1);
    }

    @Test
    @DisplayName("过期桶不进读数、不被扣（懒过期，无清理任务）")
    void expiredBucket_excludedFromBalanceAndDeduction() {
        long expiredBucketId = insertDrawCountBucket(USER_ID, 2, 2, LocalDateTime.now().minusDays(1), "PURCHASE", "96006");
        insertDrawCountBucket(USER_ID, 1, 1, LocalDateTime.now().plusDays(10), "PURCHASE", "96007");

        assertThat(drawCountAccountService.getBalance(USER_ID)).as("读路径懒过期：过期量不进读数").isEqualTo(1);

        assertThat(drawCountAccountService.deductForDraw(USER_ID, "97007")).isTrue();
        assertThat(drawCountAccountService.getBalance(USER_ID)).as("扣次后归零").isZero();
        assertThat(bucketRemainOf(expiredBucketId)).as("过期桶余量原样：不进读数，也没有任务去改写它").isEqualTo(2);
        assertThat(bucketCountOf(USER_ID)).as("不删行").isEqualTo(2);
        // 两查拼装（SQL 层零 JOIN）：先单表查扣减明细，再单表核对该桶真实存在
        List<String> detailBucketIds = jdbcTemplate.queryForList(
                "SELECT bucket_id FROM draw_count_detail WHERE ref_id = '97007'", String.class);
        assertThat(detailBucketIds).as("过期桶不被扣，扣的是有效桶：明细恰一行").hasSize(1);
        Integer bucketExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM draw_count_bucket WHERE bucket_id = ?", Integer.class, detailBucketIds.get(0));
        assertThat(bucketExists).as("明细指向的是有效桶（过期行留在表里但不参与扣减）").isEqualTo(1);
    }

    @Test
    @DisplayName("行为返利入账（DRAW_COUNT 规则）：落 REBATE 桶；未配规则则不做")
    void creditForRebate_followsRebateConfig() {
        assertThat(drawCountAccountService.creditForRebate("sign", USER_ID, "98001"))
                .as("没配规则").isFalse();

        jdbcTemplate.update("""
                        INSERT INTO action_rebate_config (action_type, rebate_type, rebate_name, rebate_desc,
                                                          rebate_value, config_id, state, create_time, update_time)
                        VALUES ('sign', 'DRAW_COUNT', '签到返次数', NULL, 2, 1, 'open', NOW(), NOW())
                        """);
        assertThat(drawCountAccountService.creditForRebate("sign", USER_ID, "98001")).isTrue();
        assertThat(drawCountAccountService.creditForRebate("sign", USER_ID, "98001"))
                .as("同一发放幂等键重放不重复加").isTrue();

        assertThat(bucketCountOf(USER_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason_type FROM draw_count_bucket WHERE user_id = ?", String.class, USER_ID))
                .isEqualTo("REBATE");
        assertThat(drawCountAccountService.getBalance(USER_ID)).isEqualTo(2);
    }
}
