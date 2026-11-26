package com.betterschool.integration.raffle;

import com.betterschool.domain.raffle.service.orphan.IRaffleOrphanPrereserveService;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.trigger.raffle.job.OrphanPrereserveScanJob;
import com.betterschool.types.common.IdempotencyKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 孤儿预扣对账（spec User Stories 14–16/18）：抽奖「预扣 → 落库」跨合并器事务的缝被中断时
 * （预扣明细在、draw_record 不在、次数已扣），对账 job 在一个扫描周期内收敛——
 * <ul>
 *   <li>决策可重放（活动仍有效）→ 以原 recordId 重放落库后半段（不重复扣次数、预扣撞唯一键按已扣回放）；
 *       决策重跑漂移到别的奖品时，原奖品的预扣被回补（守恒判据 B3：回补与删明细行同事务）；</li>
 *   <li>决策不可重放（活动已结束 / 下线）→ 库存回补（PHYSICAL waiting→surplus / VIRTUAL issued→surplus）
 *       + 次数退还（次数桶幂等键入账）；</li>
 *   <li>重试耗尽进死信（只 ERROR 日志 + 人工 SQL 重置自愈）。</li>
 * </ul>
 * 测试缝：域扫描入口 {@link IRaffleOrphanPrereserveService#reconcile(int)} 直调（域层缝，
 * 不走 HTTP），job 触发层只测「触发 → 调用域扫描入口」一条断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("抽奖 · 孤儿预扣对账收敛")
class OrphanPrereserveReconcileIntegrationTest extends TestingEnvironment {

    private static final String USER = "user-8801";
    private static final String STRATEGY = "8900";
    /** 原决策命中的奖品（孤儿预扣的 award_id 快照） */
    private static final String AWARD_X = "8901";
    /** 决策重跑漂移目标（VIRTUAL 档） */
    private static final String AWARD_Y = "8902";
    /** 第二条孤儿（VIRTUAL 档，验 issued→surplus 回补） */
    private static final String AWARD_Z = "8903";

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected IRaffleOrphanPrereserveService raffleOrphanPrereserveService;

    /** 表隔离：对账单 + 抽奖配置/落库 + 次数账务 + 奖品库存账户（outbox 供落库事件的同事务写入） */
    @BeforeEach
    void truncateTables() {
        List.of("draw_orphan_prereserve_order",
                        "draw_activity", "draw_strategy", "draw_strategy_award",
                        "draw_record", "draw_record_detail",
                        "draw_count_bucket", "draw_count_detail",
                        "draw_strategy_award_inventory", "draw_strategy_award_inventory_detail",
                        "mq_outbox")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
    }

    // ---------- 前置态构造 ----------

    /** 活动开（窗口覆盖当前时间）；不铺 = 活动已结束 / 下线，决策不可重放 */
    private void insertOpenActivity() {
        jdbcTemplate.update("""
                        INSERT INTO draw_activity (activity_id, activity_name, strategy_id, begin_date_time,
                                                   end_date_time, state, create_time, update_time)
                        VALUES ('8900', '孤儿对账用例活动', ?, ?, ?, 'open', NOW(), NOW())
                        """,
                STRATEGY,
                Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2027, 12, 31, 23, 59, 59)));
    }

    /** 策略行不带规则 → 链走 default 节点 → 未预热抛 4311 → 域内降级 DB 直算（确定性：单档 rate=1） */
    private void insertStrategyRow() {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy (strategy_id, strategy_desc, rule, create_time, update_time)
                        VALUES (?, '孤儿对账用例策略', NULL, NOW(), NOW())
                        """, STRATEGY);
    }

    private void insertStrategyAward(String awardId, String awardType, String rate, String invId) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_award (strategy_id, award_id, award_name, award_desc, award_type,
                                                        award_rate, level, inv_id, type, sort, create_time, update_time)
                        VALUES (?, ?, '孤儿对账奖品', NULL, ?, ?, 1, ?, 'normal', 0, NOW(), NOW())
                        """, STRATEGY, awardId, awardType, new java.math.BigDecimal(rate), invId);
    }

    /** 奖品库存账户直铺（conservation 用例同口径：台账三列即真相） */
    private void insertAwardLedger(String awardId, String type, long surplus, long waiting, long issued) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_award_inventory (award_id, type, surplus_qty, waiting_qty,
                                                                   issued_qty, value_qty, state, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, NULL, 0, NOW(), NOW())
                        """, awardId, type, surplus, waiting, issued);
    }

    /** 铺一行「预扣在、记录不在」的孤儿预扣明细（模拟合并器事务已提交、落库前宕机） */
    private void insertOrphanPrereserve(String awardId, String recordId, long quantity) {
        jdbcTemplate.update("""
                        INSERT INTO draw_strategy_award_inventory_detail (award_id, flow_type, quantity, biz_type,
                                                                          biz_id, create_time, update_time)
                        VALUES (?, 1, ?, 'LOTTERY_DRAW', ?, DATE_SUB(NOW(), INTERVAL 11 MINUTE),
                                DATE_SUB(NOW(), INTERVAL 11 MINUTE))
                        """, awardId, quantity, recordId);
    }

    /** 铺「次数已扣」的事实（桶 remain 已减 + 扣次明细 ref_id = recordId） */
    private void insertCountConsumption(String userId, String recordId) {
        String bucketId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                        INSERT INTO draw_count_bucket (bucket_id, user_id, amount, remain, expire_at,
                                                       reason_type, ref_id, create_time, update_time)
                        VALUES (?, ?, 1, 0, DATE_ADD(NOW(), INTERVAL 30 DAY), 'PURCHASE', 'seed', NOW(), NOW())
                        """, bucketId, userId);
        jdbcTemplate.update("""
                        INSERT INTO draw_count_detail (user_id, bucket_id, ref_id, create_time, update_time)
                        VALUES (?, ?, ?, NOW(), NOW())
                        """, userId, bucketId, recordId);
    }

    // ---------- 断言助手 ----------

    private long ledgerOf(String awardId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM draw_strategy_award_inventory WHERE award_id = ?", Long.class, awardId);
    }

    private int orderStateOf(String recordId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM draw_orphan_prereserve_order WHERE record_id = ?", Integer.class, recordId);
    }

    private long flowDetailCountOf(String awardId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_strategy_award_inventory_detail WHERE award_id = ? AND flow_type = 1",
                Long.class, awardId);
        return count == null ? 0 : count;
    }

    private int drawRecordCountOf(String recordId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_record WHERE record_id = ?", Integer.class, recordId);
        return count == null ? 0 : count;
    }

    private String recordAwardOf(String recordId) {
        return jdbcTemplate.queryForObject(
                "SELECT award_id FROM draw_record WHERE record_id = ?", String.class, recordId);
    }

    private int countDetailOf(String recordId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_detail WHERE ref_id = ?", Integer.class, recordId);
        return count == null ? 0 : count;
    }

    private int bucketCountOf(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_count_bucket WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private long refundRemainOf(String recordId) {
        Long remain = jdbcTemplate.queryForObject(
                "SELECT remain FROM draw_count_bucket WHERE reason_type = 'ORPHAN_REFUND' AND ref_id = ?",
                Long.class, recordId);
        return remain == null ? -1 : remain;
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("第二缝：job 触发 → 调用域扫描入口（只做转调）")
    void jobTrigger_delegatesToDomainReconcileEntry() {
        IRaffleOrphanPrereserveService domainService = mock(IRaffleOrphanPrereserveService.class);
        OrphanPrereserveScanJob job = new OrphanPrereserveScanJob(domainService);

        job.execute();

        verify(domainService).reconcile(anyInt());
        verifyNoMoreInteractions(domainService);
    }

    @Test
    @DisplayName("决策可重放：以原 recordId 补落库，不重复扣次数、预扣撞唯一键按已扣回放")
    void orphanReplayable_persistsRecordWithOriginalKeysWithoutDoubleDeduction() {
        insertOpenActivity();
        insertStrategyRow();
        insertStrategyAward(AWARD_X, "PHYSICAL", "1.0000", "pool-x");
        insertAwardLedger(AWARD_X, "PHYSICAL", 9, 1, 0);
        String recordId = IdempotencyKeys.of(USER, "token-orphan-replay");
        insertOrphanPrereserve(AWARD_X, recordId, 1);
        insertCountConsumption(USER, recordId);

        int converged = raffleOrphanPrereserveService.reconcile(100);

        assertThat(converged).isEqualTo(1);
        assertThat(orderStateOf(recordId)).as("对账单收敛到 DONE").isEqualTo(1);
        assertThat(drawRecordCountOf(recordId)).as("中奖记录以原幂等键补落库").isEqualTo(1);
        assertThat(recordAwardOf(recordId)).as("决策重跑命中同一奖品（无漂移）").isEqualTo(AWARD_X);
        assertThat(ledgerOf(AWARD_X, "surplus_qty")).as("不重复预扣：surplus 不变").isEqualTo(9);
        assertThat(ledgerOf(AWARD_X, "waiting_qty")).as("原预扣即是记录的占用：waiting 不变").isEqualTo(1);
        assertThat(flowDetailCountOf(AWARD_X)).as("预扣明细不重复落行").isEqualTo(1);
        assertThat(countDetailOf(recordId)).as("不重复扣次数：扣次明细仍一行").isEqualTo(1);
        assertThat(bucketCountOf(USER)).as("不触发退还：无新桶").isEqualTo(1);
    }

    @Test
    @DisplayName("决策重跑漂移：记录落在新奖品上，原奖品的预扣被回补（含删预扣明细行，守恒不破）")
    void orphanReplayDrift_restitutesOriginalAwardPrereserve() {
        insertOpenActivity();
        insertStrategyRow();
        // 当前策略只有 Y（rate=1 必中）：孤儿预扣的 X 不在当前策略里，决策重跑必漂移
        insertStrategyAward(AWARD_Y, "VIRTUAL", "1.0000", "pool-y");
        insertAwardLedger(AWARD_X, "PHYSICAL", 9, 1, 0);
        insertAwardLedger(AWARD_Y, "VIRTUAL", 50, 0, 0);
        String recordId = IdempotencyKeys.of(USER, "token-orphan-drift");
        insertOrphanPrereserve(AWARD_X, recordId, 1);
        insertCountConsumption(USER, recordId);

        int converged = raffleOrphanPrereserveService.reconcile(100);

        assertThat(converged).isEqualTo(1);
        assertThat(orderStateOf(recordId)).isEqualTo(1);
        assertThat(recordAwardOf(recordId)).as("记录落在重跑决策的奖品上（接受漂移）").isEqualTo(AWARD_Y);
        assertThat(ledgerOf(AWARD_Y, "issued_qty")).as("新奖品正常预扣发放").isEqualTo(1);
        assertThat(flowDetailCountOf(AWARD_Y)).isEqualTo(1);
        assertThat(ledgerOf(AWARD_X, "waiting_qty")).as("原奖品 waiting 回补清零").isZero();
        assertThat(ledgerOf(AWARD_X, "surplus_qty")).as("原奖品余量还原").isEqualTo(10);
        assertThat(flowDetailCountOf(AWARD_X)).as("预扣明细随回补删除（B3 守恒：waiting+issued=Σflow1）").isZero();
        assertThat(bucketCountOf(USER)).as("记录已落库不退次数").isEqualTo(1);
    }

    @Test
    @DisplayName("决策不可重放（活动已结束）：库存回补（waiting/issued→surplus）+ 次数退还，记录不落库")
    void orphanNotReplayable_restitutesInventoryAndRefundsCount() {
        // 不铺活动 = 查无进行中活动 → 4302 → 走回补路径
        insertStrategyRow();
        insertAwardLedger(AWARD_X, "PHYSICAL", 9, 1, 0);
        insertAwardLedger(AWARD_Z, "VIRTUAL", 49, 0, 1);
        String recordX = IdempotencyKeys.of(USER, "token-orphan-dead-x");
        String recordZ = IdempotencyKeys.of(USER, "token-orphan-dead-z");
        insertOrphanPrereserve(AWARD_X, recordX, 1);
        insertOrphanPrereserve(AWARD_Z, recordZ, 1);
        insertCountConsumption(USER, recordX);
        insertCountConsumption(USER, recordZ);

        int converged = raffleOrphanPrereserveService.reconcile(100);

        assertThat(converged).isEqualTo(2);
        assertThat(drawRecordCountOf(recordX)).as("不可重放不落库").isZero();
        assertThat(drawRecordCountOf(recordZ)).isZero();
        assertThat(ledgerOf(AWARD_X, "waiting_qty")).as("PHYSICAL：waiting→surplus 条件转移").isZero();
        assertThat(ledgerOf(AWARD_X, "surplus_qty")).isEqualTo(10);
        assertThat(flowDetailCountOf(AWARD_X)).isZero();
        assertThat(ledgerOf(AWARD_Z, "issued_qty")).as("VIRTUAL：issued 按台账语义条件回退").isZero();
        assertThat(ledgerOf(AWARD_Z, "surplus_qty")).isEqualTo(50);
        assertThat(flowDetailCountOf(AWARD_Z)).isZero();
        assertThat(refundRemainOf(recordX)).as("次数退还：ORPHAN_REFUND 桶幂等入账").isEqualTo(1);
        assertThat(refundRemainOf(recordZ)).isEqualTo(1);
        assertThat(orderStateOf(recordX)).isEqualTo(1);
        assertThat(orderStateOf(recordZ)).isEqualTo(1);
    }

    @Test
    @DisplayName("重试耗尽进死信（只留 ERROR 日志）；人工 SQL 重置后可自愈")
    void retryExhausted_movesToDeadLetter_thenHealedByManualReset() {
        insertStrategyRow();
        insertAwardLedger(AWARD_X, "PHYSICAL", 9, 1, 0);
        String recordId = IdempotencyKeys.of(USER, "token-orphan-dead");
        // quantity=0 撞退还入账的参数守卫，模拟「收敛动作持续失败」
        insertOrphanPrereserve(AWARD_X, recordId, 0);
        insertCountConsumption(USER, recordId);

        for (int attempt = 1; attempt <= 5; attempt++) {
            raffleOrphanPrereserveService.reconcile(100);
            jdbcTemplate.update(
                    "UPDATE draw_orphan_prereserve_order SET next_retry_at = NULL WHERE record_id = ?", recordId);
        }

        assertThat(orderStateOf(recordId)).as("重试耗尽进死信").isEqualTo(2);
        Integer retryCount = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM draw_orphan_prereserve_order WHERE record_id = ?", Integer.class, recordId);
        assertThat(retryCount).isEqualTo(5);
        assertThat(ledgerOf(AWARD_X, "waiting_qty")).as("死信不动台账（quantity=0 回补为无操作）").isEqualTo(1);
        assertThat(refundRemainOf(recordId)).as("退还从未成功").isEqualTo(-1);

        // 人工 SQL 重置（修复数量 + 置回待收敛）→ 下个扫描周期自愈：回补 + 退还 + DONE
        jdbcTemplate.update(
                "UPDATE draw_orphan_prereserve_order SET quantity = 1, state = 0 WHERE record_id = ?", recordId);
        raffleOrphanPrereserveService.reconcile(100);

        assertThat(orderStateOf(recordId)).as("人工重置后收敛到 DONE").isEqualTo(1);
        assertThat(ledgerOf(AWARD_X, "waiting_qty")).isZero();
        assertThat(ledgerOf(AWARD_X, "surplus_qty")).isEqualTo(10);
        assertThat(refundRemainOf(recordId)).isEqualTo(1);
    }
}
