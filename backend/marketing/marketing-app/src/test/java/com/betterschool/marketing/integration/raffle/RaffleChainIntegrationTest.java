package com.betterschool.marketing.integration.raffle;

import com.betterschool.marketing.domain.raffle.service.chain.IChainNode;
import com.betterschool.marketing.domain.raffle.service.chain.factory.RaffleChainFactory;
import com.betterschool.marketing.domain.raffle.service.warmup.IRaffleWarmupService;
import com.betterschool.marketing.domain.raffle.model.valobj.RaffleRuleNodeVO;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.id.UuidIdGenerator;
import com.betterschool.marketing.types.raffle.Constants;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 抽奖责任链集成用例（票 11 验收 2）：黑名单命中短路、权重按配置命中指定奖品档、无命中走默认兜底、
 * ChainFactory 按 DB 配置改链后行为随之变、未预热时默认节点抛降级态异常（DB 直算入口归票 13 接）。
 * 本票不接主链路：只验骨架可独立出结果，不扣次数、不扣库存、不写 draw_record。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("抽奖 · 责任链")
class RaffleChainIntegrationTest extends TestingEnvironment {

    private static final String USER_ID = "user-2001";

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected RedissonClient redissonClient;
    @Autowired
    protected RaffleChainFactory raffleChainFactory;
    @Autowired
    protected IRaffleWarmupService raffleWarmupService;

    @BeforeEach
    void truncateTables() {
        List.of("draw_activity", "draw_strategy", "draw_strategy_award", "draw_strategy_weight",
                        "draw_record", "draw_record_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        redissonClient.getKeys().deleteByPattern(Constants.RAFFLE_KEY_PREFIX + "*");
    }

    // ---- 夹具 ----

    private String insertStrategy(String rule) {
        String strategyId = UuidIdGenerator.INSTANCE.nextId();
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy (strategy_id, strategy_desc, rule, create_time, update_time)
                        VALUES (?, '链测试策略', ?, NOW(), NOW())
                        """, strategyId, rule);
        return strategyId;
    }

    private void insertAward(String strategyId, String awardId, String rate, String type) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_award (strategy_id, award_id, award_name, award_desc, award_type,
                                                        award_rate, level, inv_id, type, sort, create_time, update_time)
                        VALUES (?, ?, ?, NULL, 'VIRTUAL', ?, 1, 0, ?, 0, NOW(), NOW())
                        """,
                strategyId, awardId, "奖品" + awardId, new BigDecimal(rate), type);
    }

    private void insertWeight(String strategyId, int count, String weights) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_weight (strategy_id, count, weights, create_time, update_time)
                        VALUES (?, ?, ?, NOW(), NOW())
                        """, strategyId, count, weights);
    }

    /** 黑名单用户集合由风控侧写入（本域无黑名单用户表） */
    private void blacklistUser(String strategyId, String userId) {
        redissonClient.getBucket(Constants.RAFFLE_BLACKLIST_USER_KEY + strategyId + ":" + userId,
                org.redisson.client.codec.StringCodec.INSTANCE).set("exist");
    }

    /** 造 n 行历史抽奖记录 = 该用户在该策略下累计抽数 n（权重档命中判据） */
    private void insertDrawRecords(String strategyId, String userId, int n) {
        for (int i = 0; i < n; i++) {
            jdbcTemplate.update("""
                            INSERT INTO draw_record (record_id, user_id, activity_id, strategy_id, award_id,
                                                     award_name, award_desc, award_type, level, inv_id, state,
                                                     create_time, update_time)
                            VALUES (?, ?, 1, ?, 1, 'x', NULL, 'VIRTUAL', 1, 0, '已完成', NOW(), NOW())
                            """,
                    UuidIdGenerator.INSTANCE.nextId(), userId, strategyId);
        }
    }

    private RaffleChainFactory.ChainAwardVO execute(String strategyId, String userId) {
        IChainNode headNode = raffleChainFactory.getFirstChainNode(strategyId);
        return headNode.execute(userId, strategyId, "00000000-0000-4000-8000-000000000001");
    }

    // ---- 用例 ----

    @Test
    @DisplayName("黑名单命中：短路返回 blacklist 奖品位，不走权重/默认")
    void blacklistHit_shortCircuits() {
        String strategyId = insertStrategy("rule_blacklist,rule_weight");
        String award1 = UuidIdGenerator.INSTANCE.nextId();
        String blacklistAward = UuidIdGenerator.INSTANCE.nextId();
        insertAward(strategyId, award1, "1.0000", "normal");
        insertAward(strategyId, blacklistAward, "0.0000", "blacklist");
        insertWeight(strategyId, 1, String.valueOf(award1));
        raffleWarmupService.warmupByStrategyId(strategyId);
        blacklistUser(strategyId, USER_ID);
        insertDrawRecords(strategyId, USER_ID, 5);

        RaffleChainFactory.ChainAwardVO result = execute(strategyId, USER_ID);

        assertThat(result.getLogicModel()).as("黑名单路径短路，不落到权重").isEqualTo(RaffleRuleNodeVO.RULE_BLACKLIST);
        assertThat(result.getAwardId()).isEqualTo(blacklistAward);
    }

    @Test
    @DisplayName("权重保底：累计抽数达档取「满足阈值中最大的一档」，从该档奖品池出奖")
    void weightHit_drawsFromMatchedTierPool() {
        String strategyId = insertStrategy("rule_blacklist,rule_weight");
        String award1 = UuidIdGenerator.INSTANCE.nextId();
        String award2 = UuidIdGenerator.INSTANCE.nextId();
        String award3 = UuidIdGenerator.INSTANCE.nextId();
        // normal 池合计 = 1.0000；权重档 5 抽保底 award1、10 抽保底 award2
        insertAward(strategyId, award1, "0.2000", "normal");
        insertAward(strategyId, award2, "0.3000", "normal");
        insertAward(strategyId, award3, "0.5000", "normal");
        insertWeight(strategyId, 5, String.valueOf(award1));
        insertWeight(strategyId, 10, String.valueOf(award2));
        raffleWarmupService.warmupByStrategyId(strategyId);

        // 7 抽：达 5 档未达 10 档 → 恒出 award1（该档池只有它）
        insertDrawRecords(strategyId, USER_ID, 7);
        RaffleChainFactory.ChainAwardVO result = execute(strategyId, USER_ID);
        assertThat(result.getLogicModel()).isEqualTo(RaffleRuleNodeVO.RULE_WEIGHT);
        assertThat(result.getWeightCount()).isEqualTo(5);
        assertThat(result.getAwardId()).isEqualTo(award1);

        // 12 抽：升到 10 档 → 恒出 award2
        insertDrawRecords(strategyId, USER_ID, 5);
        RaffleChainFactory.ChainAwardVO result12 = execute(strategyId, USER_ID);
        assertThat(result12.getWeightCount()).isEqualTo(10);
        assertThat(result12.getAwardId()).isEqualTo(award2);

        // 无权重档命中（0 抽）→ 默认兜底
        String freshUser = "user-2002";
        RaffleChainFactory.ChainAwardVO defaultResult = execute(strategyId, freshUser);
        assertThat(defaultResult.getLogicModel()).isEqualTo(RaffleRuleNodeVO.RULE_DEFAULT);
        assertThat(defaultResult.getAwardId()).isIn(award1, award2, award3);
    }

    @Test
    @DisplayName("无命中走默认兜底：从全量 normal 概率表出奖")
    void noHit_fallsThroughToDefault() {
        String strategyId = insertStrategy("rule_blacklist,rule_weight");
        String award1 = UuidIdGenerator.INSTANCE.nextId();
        String award2 = UuidIdGenerator.INSTANCE.nextId();
        insertAward(strategyId, award1, "0.3000", "normal");
        insertAward(strategyId, award2, "0.7000", "normal");
        raffleWarmupService.warmupByStrategyId(strategyId);

        RaffleChainFactory.ChainAwardVO result = execute(strategyId, USER_ID);

        assertThat(result.getLogicModel()).isEqualTo(RaffleRuleNodeVO.RULE_DEFAULT);
        assertThat(result.getAwardId()).isIn(award1, award2);
        assertThat(result.getWeightCount()).isNull();
    }

    @Test
    @DisplayName("ChainFactory 按 DB 配置改链：rule 从空改为 rule_blacklist 后重预热，行为随之变")
    void chainFactory_rebuildsAfterRuleChange() {
        String strategyId = insertStrategy(null);
        String award1 = UuidIdGenerator.INSTANCE.nextId();
        String blacklistAward = UuidIdGenerator.INSTANCE.nextId();
        insertAward(strategyId, award1, "1.0000", "normal");
        insertAward(strategyId, blacklistAward, "0.0000", "blacklist");
        raffleWarmupService.warmupByStrategyId(strategyId);
        blacklistUser(strategyId, USER_ID);

        assertThat(execute(strategyId, USER_ID).getLogicModel())
                .as("没配 rule：黑名单用户也只走默认兜底").isEqualTo(RaffleRuleNodeVO.RULE_DEFAULT);

        jdbcTemplate.update("UPDATE draw_strategy SET rule = ? WHERE strategy_id = ?",
                "rule_blacklist", strategyId);
        raffleWarmupService.warmupByStrategyId(strategyId);

        RaffleChainFactory.ChainAwardVO result = execute(strategyId, USER_ID);
        assertThat(result.getLogicModel()).as("重预热失效链缓存后按新配置重组").isEqualTo(RaffleRuleNodeVO.RULE_BLACKLIST);
        assertThat(result.getAwardId()).isEqualTo(blacklistAward);
    }

    @Test
    @DisplayName("未预热：默认节点抛 RAFFLE_WARMUP_MISSING（4311），DB 直算降级入口归票 13")
    void coldTable_throwsWarmupMissing() {
        String strategyId = insertStrategy(null);
        String award1 = UuidIdGenerator.INSTANCE.nextId();
        insertAward(strategyId, award1, "1.0000", "normal");

        assertThatThrownBy(() -> execute(strategyId, USER_ID))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getCode()).isEqualTo("4311"));
    }
}
