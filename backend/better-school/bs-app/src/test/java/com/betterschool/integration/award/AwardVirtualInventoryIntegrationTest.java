package com.betterschool.integration.award;

import com.betterschool.domain.award.service.IAwardInventoryService;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.types.award.AwardInventoryTypeEnum;
import com.betterschool.types.award.AwardBizTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 虚拟奖品库存账户（积分 / 抽奖次数）预扣集成用例：
 * 账户由运营直接投放（{@code value_qty} 即单次面额），预扣进进程内合并器，
 * **返回即台账已动**——{@code surplus -= N, issued += N} 已提交、明细恰好一行，
 * 没有第二步、没有 Redis 桶（台账三列是唯一真相）。
 * 守恒与明细等式由 {@code AwardInventoryConservationIntegrationTest}（B0–B3）锁住。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("库存两段式 · 虚拟奖品库存账户")
class AwardVirtualInventoryIntegrationTest extends TestingEnvironment {

    private static final String AWARD_ID = "900101";
    /** 抽奖记录 id（明细 biz_id）：同一笔业务重复预扣靠它撞 uk_award_biz */
    private static final String RECORD_ID = "93001";

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IAwardInventoryService awardInventoryService;

    @BeforeEach
    void resetFixtures() {
        List.of("award_inventory", "award_inventory_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        // 运营直接投放：虚拟账户 surplus 100、单次面额 50
        jdbcTemplate.update("""
                        INSERT INTO award_inventory (award_id, type, surplus_qty, waiting_qty, issued_qty,
                                                     value_qty, state, create_time, update_time)
                        VALUES (?, 'VIRTUAL', 100, 0, 0, 50, 0, NOW(), NOW())
                        """, AWARD_ID);
    }

    private boolean preDeduct(long quantity, String recordId) {
        return awardInventoryService.preDeduct(AwardInventoryTypeEnum.VIRTUAL, AWARD_ID, quantity,
                AwardBizTypes.LOTTERY_DRAW, recordId);
    }

    private Map<String, Object> accountRow() {
        return jdbcTemplate.queryForMap(
                "SELECT type, surplus_qty, waiting_qty, issued_qty, value_qty, state"
                        + " FROM award_inventory WHERE award_id = ?", AWARD_ID);
    }

    private long longOf(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private long detailCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM award_inventory_detail WHERE award_id = ? AND flow_type = 1",
                Long.class, AWARD_ID);
        assertThat(count).isNotNull();
        return count;
    }

    private long detailQuantitySum() {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = 1",
                Long.class, AWARD_ID);
        return sum == null ? 0 : sum;
    }

    @Test
    @DisplayName("直接投放：账户类型 = VIRTUAL，value_qty 由运营给定")
    void directSeed_createsVirtualAccountWithValueQty() {
        var account = accountRow();
        assertThat(account.get("type")).as("虚拟账户 = VIRTUAL").isEqualTo("VIRTUAL");
        assertThat(longOf(account, "surplus_qty")).isEqualTo(100L);
        assertThat(longOf(account, "waiting_qty")).as("虚拟账户 waiting 恒 0").isZero();
        assertThat(longOf(account, "value_qty")).as("面额配置由投放给定").isEqualTo(50L);
    }

    @Test
    @DisplayName("预扣返回即台账已动：surplus -= N、issued += N、明细恰好一行，三列守恒")
    void preDeduct_commitsLedgerImmediately() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        var ledger = accountRow();
        assertThat(longOf(ledger, "surplus_qty")).as("surplus 已减（没有第二步）").isEqualTo(97L);
        assertThat(longOf(ledger, "issued_qty")).as("虚拟账户直接进 issued").isEqualTo(3L);
        assertThat(longOf(ledger, "waiting_qty")).as("虚拟账户 waiting 恒 0").isZero();
        assertThat(((Number) ledger.get("state")).intValue()).as("仍有余量，state 保持 open").isZero();

        assertThat(detailCount()).as("明细恰好一行").isEqualTo(1);
        Map<String, Object> detail = jdbcTemplate.queryForMap(
                "SELECT quantity, biz_type, biz_id, create_time FROM award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = 1", AWARD_ID);
        assertThat(((Number) detail.get("quantity")).longValue()).isEqualTo(3L);
        assertThat(detail.get("biz_type")).as("业务类型 = 抽奖预扣").isEqualTo("LOTTERY_DRAW");
        assertThat((String) detail.get("biz_id")).as("业务 ID = draw_record.record_id")
                .isEqualTo(RECORD_ID);
        assertThat(detail.get("create_time")).as("create_time 由 DB NOW() 写，非 null").isNotNull();

        // B2 三列守恒：分配转入量 100 = surplus + waiting(恒 0) + issued
        assertThat(longOf(ledger, "surplus_qty") + longOf(ledger, "issued_qty")).isEqualTo(100L);
        // B3 明细等式：waiting + issued == SUM(flow_type = 1)
        assertThat(longOf(ledger, "issued_qty")).isEqualTo(detailQuantitySum());
    }

    @Test
    @DisplayName("重复预扣同一 recordId：撞 uk_award_biz 按成功回放，明细仍一行、净扣一份")
    void preDeduct_sameRecordId_replaysWithoutSecondDeduction() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(preDeduct(3, RECORD_ID))
                .as("第二次撞业务唯一键，按成功回放").isTrue();

        assertThat(detailCount()).as("明细只有一行").isEqualTo(1);
        var ledger = accountRow();
        assertThat(longOf(ledger, "issued_qty")).as("净扣一份").isEqualTo(3L);
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(97L);
    }

    @Test
    @DisplayName("账户 exhausted 后重放同一 recordId：state 检查不吞掉重放；别的 recordId 拒绝")
    void preDeduct_sameRecordId_afterExhausted_replays() {
        jdbcTemplate.update("UPDATE award_inventory SET surplus_qty = 3 WHERE award_id = ?",
                AWARD_ID);
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(((Number) accountRow().get("state")).intValue())
                .as("surplus 恰好扣空 → exhausted").isEqualTo(1);

        assertThat(preDeduct(3, RECORD_ID))
                .as("台账非 open 但明细已存在 → 成功回放").isTrue();
        assertThat(detailCount()).as("重放不落第二行").isEqualTo(1);
        assertThat(longOf(accountRow(), "issued_qty")).as("重放不再扣账").isEqualTo(3L);

        assertThat(preDeduct(1, RECORD_ID + "-next"))
                .as("exhausted 台账不可再预扣").isFalse();
        assertThat(detailCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("余量不足：整批 INSUFFICIENT，零副作用（无明细、台账不变）")
    void preDeduct_insufficient_isSideEffectFree() {
        assertThat(preDeduct(101, RECORD_ID)).isFalse();

        assertThat(detailCount()).as("不落明细").isZero();
        var ledger = accountRow();
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(100L);
        assertThat(longOf(ledger, "issued_qty")).isZero();
        assertThat(((Number) ledger.get("state")).intValue()).isZero();
    }

    @Test
    @DisplayName("不限量账户（surplus = -1）：守卫放行、surplus 不减，issued 照加")
    void preDeduct_unlimitedAccount_neverDecreasesSurplus() {
        jdbcTemplate.update("UPDATE award_inventory SET surplus_qty = -1 WHERE award_id = ?",
                AWARD_ID);
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        var ledger = accountRow();
        assertThat(longOf(ledger, "surplus_qty")).as("不限量不扣 surplus").isEqualTo(-1L);
        assertThat(longOf(ledger, "issued_qty")).isEqualTo(3L);
        assertThat(detailQuantitySum()).isEqualTo(3L);
    }
}
