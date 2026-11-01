package com.betterschool.marketing.integration.inventory;

import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 虚拟权益池（积分 / 抽奖次数）预扣集成用例（票 06）：预扣进进程内合并器，**返回即台账已动**——
 * {@code surplus -= N, issued += N} 已提交、明细恰好一行，没有第二步、没有 Redis 桶（桶层已删，
 * 台账三列是唯一真相）。旧合并机制（时间格 / 兜底 / 消息）已随票 08 整条删除，
 * 守恒与明细等式由 {@code InventoryQuotaConservationIntegrationTest}（B2 / B3）锁住。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("库存两段式 · 虚拟权益池")
class VirtualInventoryIntegrationTest extends TestingEnvironment {

    private static final long INV_ID = 9001L;
    /** 抽奖记录 id（明细 biz_id）：同一笔业务重复预扣靠它撞 uk_biz */
    private static final long RECORD_ID = 93001L;

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IInventoryQuotaService inventoryQuotaService;

    @BeforeEach
    void resetFixtures() {
        List.of("virtual_inventory_quota", "virtual_inventory_quota_detail",
                "physical_marketing_quota", "physical_marketing_quota_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        insertVirtualInventory(INV_ID, "暑期签到积分池", "POINTS", 50, 100L, 0L, "open");
    }

    private boolean preDeduct(long quantity, long recordId) {
        return inventoryQuotaService.preDeduct(InventorySegmentVO.VIRTUAL, INV_ID, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
    }

    private void insertVirtualInventory(long invId, String name, String type, int value,
                                        long surplusQty, long issuedQty, String state) {
        jdbcTemplate.update("""
                        INSERT INTO virtual_inventory_quota (inv_id, name, type, value, surplus_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                invId, name, type, value, surplusQty, issuedQty, state);
    }

    private Map<String, Object> inventoryRow() {
        return jdbcTemplate.queryForMap(
                "SELECT surplus_qty, issued_qty, state FROM virtual_inventory_quota WHERE inv_id = ?", INV_ID);
    }

    private long detailCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM virtual_inventory_quota_detail WHERE inv_id = ?", Long.class, INV_ID);
        assertThat(count).isNotNull();
        return count;
    }

    private long detailQuantitySum() {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM virtual_inventory_quota_detail WHERE inv_id = ?",
                Long.class, INV_ID);
        return sum == null ? 0 : sum;
    }

    private long longOf(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    @Test
    @DisplayName("预扣返回即台账已动：surplus -= N、issued += N、明细恰好一行，三列守恒")
    void preDeduct_commitsLedgerImmediately() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        var ledger = inventoryRow();
        assertThat(longOf(ledger, "surplus_qty")).as("surplus 已减（没有第二步）").isEqualTo(97L);
        assertThat(longOf(ledger, "issued_qty")).as("虚拟段直接进 issued").isEqualTo(3L);
        assertThat(ledger.get("state")).as("仍有余量，state 保持 open").isEqualTo("open");

        assertThat(detailCount()).as("明细恰好一行").isEqualTo(1);
        Map<String, Object> detail = jdbcTemplate.queryForMap(
                "SELECT quantity, biz_type, biz_id, create_time FROM virtual_inventory_quota_detail"
                        + " WHERE inv_id = ?", INV_ID);
        assertThat(((Number) detail.get("quantity")).longValue()).isEqualTo(3L);
        assertThat(detail.get("biz_type")).as("业务类型 = 抽奖预扣").isEqualTo("LOTTERY_DRAW");
        assertThat(((Number) detail.get("biz_id")).longValue()).as("业务 ID = draw_record.record_id")
                .isEqualTo(RECORD_ID);
        assertThat(detail.get("create_time")).as("create_time 由 DB NOW() 写，非 null").isNotNull();

        // B2 三列守恒：初值 100 = surplus + waiting(恒 0) + issued
        assertThat(longOf(ledger, "surplus_qty") + longOf(ledger, "issued_qty")).isEqualTo(100L);
        // B3 明细等式：waiting + issued == SUM(quantity)
        assertThat(longOf(ledger, "issued_qty")).isEqualTo(detailQuantitySum());
    }

    @Test
    @DisplayName("重复预扣同一 recordId：撞 uk_biz 按成功回放，明细仍一行、净扣一份")
    void preDeduct_sameRecordId_replaysWithoutSecondDeduction() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(preDeduct(3, RECORD_ID))
                .as("第二次撞业务唯一键，按成功回放").isTrue();

        assertThat(detailCount()).as("明细只有一行").isEqualTo(1);
        var ledger = inventoryRow();
        assertThat(longOf(ledger, "issued_qty")).as("净扣一份").isEqualTo(3L);
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(97L);
    }

    @Test
    @DisplayName("台账 exhausted 后重放同一 recordId：state 检查不吞掉重放；别的 recordId 拒绝")
    void preDeduct_sameRecordId_afterExhausted_replays() {
        jdbcTemplate.update("UPDATE virtual_inventory_quota SET surplus_qty = 3 WHERE inv_id = ?", INV_ID);
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(inventoryRow().get("state")).as("surplus 恰好扣空 → exhausted").isEqualTo("exhausted");

        assertThat(preDeduct(3, RECORD_ID))
                .as("台账非 open 但明细已存在 → 成功回放").isTrue();
        assertThat(detailCount()).as("重放不落第二行").isEqualTo(1);
        assertThat(longOf(inventoryRow(), "issued_qty")).as("重放不再扣账").isEqualTo(3L);

        assertThat(preDeduct(1, RECORD_ID + 1))
                .as("exhausted 台账不可再预扣").isFalse();
        assertThat(detailCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("余量不足：整批 INSUFFICIENT，零副作用（无明细、台账不变）")
    void preDeduct_insufficient_isSideEffectFree() {
        assertThat(preDeduct(101, RECORD_ID)).isFalse();

        assertThat(detailCount()).as("不落明细").isZero();
        var ledger = inventoryRow();
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(100L);
        assertThat(longOf(ledger, "issued_qty")).isZero();
        assertThat(ledger.get("state")).isEqualTo("open");
    }
}
