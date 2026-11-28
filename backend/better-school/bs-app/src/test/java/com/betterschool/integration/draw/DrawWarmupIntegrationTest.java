package com.betterschool.integration.draw;

import com.betterschool.domain.draw.service.warmup.IDrawWarmupService;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.types.id.UuidIdGenerator;
import com.betterschool.types.draw.Constants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽奖决策侧表预热集成用例：概率 / 权重按 ×10000 口径灌入 Redis、配置变更后重预热生效、
 * 预热态可查（票 11 验收 3）。主链路（票 13）只消费预热结果，这里走域服务 seam。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("抽奖 · 表预热")
class DrawWarmupIntegrationTest extends TestingEnvironment {

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected RedissonClient redissonClient;
    @Autowired
    protected IDrawWarmupService drawWarmupService;

    @BeforeEach
    void truncateTables() {
        List.of("draw_activity", "draw_strategy", "draw_strategy_award", "draw_strategy_weight",
                        "draw_record", "draw_record_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        redissonClient.getKeys().deleteByPattern(Constants.DRAW_KEY_PREFIX + "*");
    }

    private String insertStrategy(String rule) {
        String strategyId = UuidIdGenerator.INSTANCE.nextId();
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy (strategy_id, strategy_desc, rule, create_time, update_time)
                        VALUES (?, '预热测试策略', ?, NOW(), NOW())
                        """, strategyId, rule);
        return strategyId;
    }

    private void insertAward(String strategyId, String awardId, String awardType, String rate, String type) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_award (strategy_id, award_id, award_name, award_desc, award_type,
                                                        award_rate, level, inv_id, type, sort, create_time, update_time)
                        VALUES (?, ?, ?, NULL, ?, ?, 1, 0, ?, 0, NOW(), NOW())
                        """,
                strategyId, awardId, "奖品" + awardId, awardType, new BigDecimal(rate), type);
    }

    private void insertWeight(String strategyId, int count, String weights) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_weight (strategy_id, count, weights, create_time, update_time)
                        VALUES (?, ?, ?, NOW(), NOW())
                        """, strategyId, count, weights);
    }

    private long awardSlotsOf(String tableKey, String awardId) {
        var table = redissonClient.getMap(tableKey);
        return table.values().stream().filter(value -> awardId.equals(String.valueOf(value))).count();
    }

    @Test
    @DisplayName("预热后 Redis 概率表与 DB 配置符：默认表 award_rate×10000、权重表池内归一化、特型奖品位入缓存")
    void warmup_buildsTablesMatchingDbConfig() {
        String strategyId = insertStrategy("rule_blacklist,rule_weight");
        String award1 = UuidIdGenerator.INSTANCE.nextId();
        String award2 = UuidIdGenerator.INSTANCE.nextId();
        String blacklistAward = UuidIdGenerator.INSTANCE.nextId();
        String fallbackAward = UuidIdGenerator.INSTANCE.nextId();
        insertAward(strategyId, award1, "VIRTUAL", "0.3000", "normal");
        insertAward(strategyId, award2, "THANKS", "0.7000", "normal");
        insertAward(strategyId, blacklistAward, "VIRTUAL", "0.0000", "blacklist");
        insertAward(strategyId, fallbackAward, "PHYSICAL", "0.0000", "fallback");
        insertWeight(strategyId, 5, award1 + "," + award2);

        drawWarmupService.warmupByStrategyId(strategyId);

        String defaultKey = Constants.DRAW_TABLE_KEY + strategyId + ":default";
        assertThat(redissonClient.getMap(defaultKey).size()).as("恒 10000 槽").isEqualTo(10000);
        assertThat(awardSlotsOf(defaultKey, award1)).as("award_rate 0.3000 → 3000 槽").isEqualTo(3000);
        assertThat(awardSlotsOf(defaultKey, award2)).isEqualTo(7000);

        String weightKey = Constants.DRAW_TABLE_KEY + strategyId + ":weight:5";
        assertThat(redissonClient.getMap(weightKey).size()).isEqualTo(10000);
        assertThat(awardSlotsOf(weightKey, award1)).as("池内归一化 0.3/(0.3+0.7) → 3000 槽").isEqualTo(3000);
        assertThat(awardSlotsOf(weightKey, award2)).isEqualTo(7000);

        assertThat(redissonClient.getBucket(Constants.DRAW_AWARD_KEY + strategyId + ":blacklist",
                org.redisson.client.codec.StringCodec.INSTANCE).get())
                .as("黑名单奖品位缓存").isEqualTo(String.valueOf(blacklistAward));
        assertThat(redissonClient.getBucket(Constants.DRAW_AWARD_KEY + strategyId + ":fallback",
                org.redisson.client.codec.StringCodec.INSTANCE).get())
                .isEqualTo(String.valueOf(fallbackAward));

        assertThat(drawWarmupService.isWarmed(strategyId)).as("预热完成 → 预热态").isTrue();
    }

    @Test
    @DisplayName("按活动预热 + 配置变更后重预热生效：新概率占比、陈旧槽位被清、预热态先无后有")
    void warmupByActivityId_andRewarmAfterConfigChange() {
        String strategyId = insertStrategy(null);
        String activityId = UuidIdGenerator.INSTANCE.nextId();
        String award1 = UuidIdGenerator.INSTANCE.nextId();
        String award2 = UuidIdGenerator.INSTANCE.nextId();
        insertAward(strategyId, award1, "VIRTUAL", "0.2500", "normal");
        insertAward(strategyId, award2, "VIRTUAL", "0.7500", "normal");
        jdbcTemplate.update("""
                        INSERT INTO draw_activity (activity_id, activity_name, strategy_id, begin_date_time,
                                                   end_date_time, state, create_time, update_time)
                        VALUES (?, '预热测试活动', ?, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY, 'open', NOW(), NOW())
                        """, activityId, strategyId);

        assertThat(drawWarmupService.isWarmed(strategyId)).as("未预热 → 降级态").isFalse();

        drawWarmupService.warmupByActivityId(activityId);
        String defaultKey = Constants.DRAW_TABLE_KEY + strategyId + ":default";
        assertThat(awardSlotsOf(defaultKey, award1)).isEqualTo(2500);

        // 配置变更（概率重配 + 撤掉一个奖品位）后重预热：新占比生效，且没有残留槽位
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 0.6000 WHERE award_id = ?", award1);
        jdbcTemplate.update("DELETE FROM draw_strategy_award WHERE award_id = ?", award2);
        String award3 = UuidIdGenerator.INSTANCE.nextId();
        insertAward(strategyId, award3, "VIRTUAL", "0.4000", "normal");
        drawWarmupService.warmupByStrategyId(strategyId);

        var table = redissonClient.getMap(defaultKey);
        assertThat(table.size()).isEqualTo(10000);
        assertThat(awardSlotsOf(defaultKey, award1)).isEqualTo(6000);
        assertThat(awardSlotsOf(defaultKey, award3)).isEqualTo(4000);
        assertThat(table.values()).as("被撤掉的奖品位不得留陈旧槽位").doesNotContain(award2);
        assertThat(drawWarmupService.isWarmed(strategyId)).isTrue();
    }

    @Test
    @DisplayName("预热态按策略隔离：预热 A 不影响 B，key 带 strategyId")
    void warmupState_isPerStrategy() {
        String warmedStrategyId = insertStrategy(null);
        String coldStrategyId = insertStrategy(null);
        String awardId = UuidIdGenerator.INSTANCE.nextId();
        insertAward(warmedStrategyId, awardId, "VIRTUAL", "1.0000", "normal");

        drawWarmupService.warmupByStrategyId(warmedStrategyId);

        assertThat(redissonClient.getBucket(Constants.DRAW_WARMUP_STATE_KEY + warmedStrategyId,
                org.redisson.client.codec.StringCodec.INSTANCE).isExists())
                .as("预热态 key 带 strategyId").isTrue();
        assertThat(redissonClient.getBucket(Constants.DRAW_WARMUP_STATE_KEY, 
                org.redisson.client.codec.StringCodec.INSTANCE).isExists())
                .as("不得存在不带 strategyId 的共用 key").isFalse();
        assertThat(drawWarmupService.isWarmed(coldStrategyId))
                .as("B 未预热仍是降级态——预热态按策略隔离").isFalse();
    }
}
