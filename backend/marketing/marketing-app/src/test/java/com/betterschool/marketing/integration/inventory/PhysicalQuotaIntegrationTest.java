package com.betterschool.marketing.integration.inventory;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.service.IInventoryAuditService;
import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.Constants;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实物营销配额集成用例（票 06 / 07）：预扣与兑换确认都进进程内合并器，**返回即台账已动**——
 * 预扣 {@code surplus -= N, waiting += N}（B2 / B3 / B4）；确认 {@code waiting -= c, issued += c}、
 * {@code type = 2} 明细恰好 c 行，没有第二步（B6）。{@code waiting} 不足 = 不变量破坏：
 * 整组回滚（连确认明细一起）+ error 日志 + 抛可重试（B5，不复活 4312、不做部分确认）。
 * 配额不足落兜底段（主档零写入）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("库存两段式 · 实物营销配额")
class PhysicalQuotaIntegrationTest extends TestingEnvironment {

    private static final long QUOTA_INV_ID = 9101L;
    /** 兜底虚拟权益（积分池），surplus 预置 999 份 */
    private static final long FALLBACK_INV_ID = 9002L;
    private static final long QUOTA_SURPLUS = 100L;
    private static final long FALLBACK_SURPLUS = 999L;
    /** 抽奖记录 id（明细 biz_id）：同一笔业务重复预扣靠 uk_biz_type 去重 */
    private static final long RECORD_ID = 95001L;

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IInventoryQuotaService inventoryQuotaService;
    @Autowired
    private IInventoryAuditService inventoryAuditService;

    @BeforeEach
    void resetFixtures() {
        List.of("virtual_inventory_quota", "virtual_inventory_quota_detail",
                "physical_marketing_quota", "physical_marketing_quota_detail", "outbox_message")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        jdbcTemplate.update("""
                        INSERT INTO physical_marketing_quota (inv_id, name, decr, surplus_qty, waiting_qty, issued_qty,
                                                              state, create_time, update_time)
                        VALUES (?, '暑期实物营销配额', NULL, ?, 0, 0, 'open', NOW(), NOW())
                        """,
                QUOTA_INV_ID, QUOTA_SURPLUS);
        jdbcTemplate.update("""
                        INSERT INTO virtual_inventory_quota (inv_id, name, type, value, surplus_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, '兜底积分池', 'POINTS', 10, ?, 0, 'open', NOW(), NOW())
                        """,
                FALLBACK_INV_ID, FALLBACK_SURPLUS);
    }

    private boolean preDeduct(long quantity, long recordId) {
        return inventoryQuotaService.preDeduct(InventorySegmentVO.QUOTA, QUOTA_INV_ID, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
    }

    private DeductOutcomeVO preDeductWithFallback(Long fallbackInvId, long quantity, long recordId) {
        InventoryDeductRequestVO primary = new InventoryDeductRequestVO(InventorySegmentVO.QUOTA, QUOTA_INV_ID,
                quantity, InventoryBizTypes.LOTTERY_DRAW, recordId);
        InventoryDeductRequestVO fallback = fallbackInvId == null ? null
                : new InventoryDeductRequestVO(InventorySegmentVO.VIRTUAL, fallbackInvId, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
        return inventoryQuotaService.preDeductWithFallback(primary, fallback);
    }

    private Map<String, Object> quotaRow() {
        return jdbcTemplate.queryForMap(
                "SELECT surplus_qty, waiting_qty, issued_qty, state FROM physical_marketing_quota WHERE inv_id = ?",
                QUOTA_INV_ID);
    }

    private long detailCount(String table, String invColumn, long invId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + invColumn + " = ?",
                Long.class, invId);
        assertThat(count).isNotNull();
        return count;
    }

    private long longOf(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    /** 该记录的兑换确认行（type = 2）：取不到即断言失败。state 列已随 D9 形态删除（确认返回即已提交） */
    private Map<String, Object> claimRow(long recordId) {
        return jdbcTemplate.queryForMap("""
                        SELECT type, biz_type, quantity
                        FROM physical_marketing_quota_detail
                        WHERE inv_id = ? AND type = ? AND biz_id = ?
                        """,
                QUOTA_INV_ID, Constants.DETAIL_TYPE_CLAIM, recordId);
    }

    private int claimRowCount(long recordId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM physical_marketing_quota_detail
                        WHERE inv_id = ? AND type = ? AND biz_id = ?
                        """,
                Integer.class, QUOTA_INV_ID, Constants.DETAIL_TYPE_CLAIM, recordId);
        return count == null ? 0 : count;
    }

    /** 预扣 / 确认都不该写 outbox（合并消息已随票 08 退休）：全表应为 0 行 */
    private int outboxRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("预扣返回即台账已动：surplus -= N、waiting += N、明细一行 type=1，三列守恒")
    void preDeduct_commitsWaitingImmediately() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        var ledger = quotaRow();
        assertThat(longOf(ledger, "surplus_qty")).as("surplus 已减（没有第二步）").isEqualTo(97L);
        assertThat(longOf(ledger, "waiting_qty")).as("waiting 已增").isEqualTo(3L);
        assertThat(longOf(ledger, "issued_qty")).isZero();
        assertThat(ledger.get("state")).as("仍有余量，state 保持 open").isEqualTo("open");

        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID)).isEqualTo(1);
        var detail = jdbcTemplate.queryForMap(
                "SELECT type, biz_type, biz_id, quantity FROM physical_marketing_quota_detail WHERE inv_id = ?",
                QUOTA_INV_ID);
        assertThat(((Number) detail.get("type")).intValue()).as("预扣结转 type=1").isEqualTo(1);
        assertThat(detail.get("biz_type")).as("业务类型 = 抽奖预扣").isEqualTo("LOTTERY_DRAW");
        assertThat(((Number) detail.get("biz_id")).longValue()).as("业务 ID = draw_record.record_id")
                .isEqualTo(RECORD_ID);
        assertThat(((Number) detail.get("quantity")).longValue()).isEqualTo(3L);

        // B2 三列守恒：初值 100 = surplus + waiting + issued
        assertThat(longOf(ledger, "surplus_qty") + longOf(ledger, "waiting_qty") + longOf(ledger, "issued_qty"))
                .isEqualTo(QUOTA_SURPLUS);
        // B3 明细等式：waiting + issued == SUM(quantity) WHERE type = 1
        Long detailSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM physical_marketing_quota_detail"
                        + " WHERE inv_id = ? AND type = ?",
                Long.class, QUOTA_INV_ID, Constants.DETAIL_TYPE_DEDUCT);
        assertThat(longOf(ledger, "waiting_qty") + longOf(ledger, "issued_qty")).isEqualTo(detailSum);
    }

    @Test
    @DisplayName("重复预扣同一 recordId：撞 uk_biz_type 按成功回放，明细仍一行、净扣一份")
    void preDeduct_sameRecordId_replaysWithoutSecondDeduction() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(preDeduct(3, RECORD_ID))
                .as("第二次撞业务唯一键，按成功回放").isTrue();

        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID))
                .as("明细只有一行").isEqualTo(1);
        var ledger = quotaRow();
        assertThat(longOf(ledger, "waiting_qty")).as("净扣一份").isEqualTo(3L);
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(97L);
    }

    @Test
    @DisplayName("主段余量被上一笔扣空后重试同一 recordId：撞键按成功回放，不落兜底、净扣一份")
    void preDeductWithFallback_sameRecordId_afterQuotaShortage_replaysWithoutFallback() {
        // 主档只剩 1 份：模拟「最后一单位被同 token 的上一笔取走」
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = 1 WHERE inv_id = ?", QUOTA_INV_ID);

        assertThat(preDeductWithFallback(FALLBACK_INV_ID, 1, RECORD_ID))
                .isEqualTo(DeductOutcomeVO.DEDUCTED);
        assertThat(longOf(quotaRow(), "surplus_qty")).isZero();

        assertThat(preDeductWithFallback(FALLBACK_INV_ID, 1, RECORD_ID))
                .as("主档已空，但明细已存在 → 撞键按成功回放，不能把同一笔再扣到兜底段")
                .isEqualTo(DeductOutcomeVO.DEDUCTED);

        assertThat(longOf(quotaRow(), "waiting_qty")).as("净扣一份").isEqualTo(1L);
        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID)).isEqualTo(1);
        assertThat(detailCount("virtual_inventory_quota_detail", "inv_id", FALLBACK_INV_ID)).isZero();
    }

    @Test
    @DisplayName("台账 exhausted 后重试同一 recordId：state 检查不吞掉重放")
    void preDeduct_sameRecordId_afterExhausted_replays() {
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = 3 WHERE inv_id = ?", QUOTA_INV_ID);
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(quotaRow().get("state")).isEqualTo("exhausted");

        assertThat(preDeduct(3, RECORD_ID))
                .as("台账非 open 但明细已存在 → 成功回放").isTrue();

        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID)).isEqualTo(1);
        assertThat(longOf(quotaRow(), "waiting_qty")).as("重放不再扣账").isEqualTo(3L);
    }

    @Test
    @DisplayName("上一笔已走兜底段：主段仍不足时直接回放 FALLBACK，不再扣第二次")
    void preDeductWithFallback_sameRecordId_whenFallbackAlreadyUsed_replays() {
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = 0 WHERE inv_id = ?", QUOTA_INV_ID);

        assertThat(preDeductWithFallback(FALLBACK_INV_ID, 1, RECORD_ID))
                .isEqualTo(DeductOutcomeVO.FALLBACK);
        assertThat(preDeductWithFallback(FALLBACK_INV_ID, 1, RECORD_ID))
                .as("兜底段明细已存在，主段扣不出时直接回放").isEqualTo(DeductOutcomeVO.FALLBACK);

        assertThat(detailCount("virtual_inventory_quota_detail", "inv_id", FALLBACK_INV_ID))
                .as("兜底段只扣一次").isEqualTo(1);
        Long fallbackIssued = jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM virtual_inventory_quota WHERE inv_id = ?", Long.class, FALLBACK_INV_ID);
        assertThat(fallbackIssued).isEqualTo(1L);
        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID)).isZero();
    }

    @Test
    @DisplayName("兑换确认（B6）：返回即转移已提交——waiting -= c、issued += c、type=2 明细恰好 c 行，无第二步")
    void confirmIssued_transfersImmediately() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        inventoryQuotaService.confirmIssued(QUOTA_INV_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 3);

        Map<String, Object> claim = claimRow(RECORD_ID);
        assertThat(((Number) claim.get("type")).intValue()).as("兑换确认 type=2").isEqualTo(Constants.DETAIL_TYPE_CLAIM);
        assertThat(claim.get("biz_type")).as("业务类型 = 兑换确认").isEqualTo(InventoryBizTypes.LOTTERY_CLAIM);
        assertThat(((Number) claim.get("quantity")).longValue()).isEqualTo(3L);
        var quota = quotaRow();
        assertThat(longOf(quota, "waiting_qty")).as("确认返回即 waiting 转走（没有第二步）").isZero();
        assertThat(longOf(quota, "issued_qty")).as("确认返回即计入 issued").isEqualTo(3L);
        assertThat(longOf(quota, "surplus_qty")).as("预扣已扣过 surplus，确认不再扣（总量不变）").isEqualTo(97L);
        assertThat(outboxRowCount()).as("确认不再发合并消息").isZero();

        // B2 三列守恒：初值 100 = surplus + waiting + issued
        assertThat(longOf(quota, "surplus_qty") + longOf(quota, "waiting_qty") + longOf(quota, "issued_qty"))
                .isEqualTo(QUOTA_SURPLUS);
    }

    @Test
    @DisplayName("重复确认同一 recordId：撞 uk_biz_type 按成功回放——明细仍一行、转移只发生一次")
    void confirmIssued_sameRecordId_replaysWithoutSecondTransfer() {
        assertThat(preDeduct(1, RECORD_ID)).isTrue();

        inventoryQuotaService.confirmIssued(QUOTA_INV_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 1);
        inventoryQuotaService.confirmIssued(QUOTA_INV_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 1);

        assertThat(claimRowCount(RECORD_ID)).as("确认明细只有一行").isEqualTo(1);
        var quota = quotaRow();
        assertThat(longOf(quota, "issued_qty")).as("转移只发生一次（第二笔撞键回放，不再转）").isEqualTo(1L);
        assertThat(longOf(quota, "waiting_qty")).isZero();
        assertThat(outboxRowCount()).as("不发合并消息").isZero();
    }

    @Test
    @DisplayName("没有预扣行直接确认（账不平）：抛可重试异常，不落确认行、台账不动")
    void confirmIssued_withoutDeductRow_throwsRetryableWithZeroSideEffects() {
        assertThatThrownBy(() -> inventoryQuotaService.confirmIssued(
                QUOTA_INV_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 1))
                .as("waiting 守卫不过 = 不变量破坏，抛可重试（不再查预扣行、不回 INVENTORY_PARAM_ERROR）")
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());

        assertThat(claimRowCount(RECORD_ID)).as("整组回滚，不落确认行").isZero();
        assertThat(outboxRowCount()).as("不发消息").isZero();
        assertThat(longOf(quotaRow(), "waiting_qty")).isZero();
        assertThat(longOf(quotaRow(), "issued_qty")).isZero();
    }

    @Test
    @DisplayName("waiting 不足（B5）：整组回滚——确认明细一并消失、台账不动、error 日志、抛可重试")
    void claimWaitingShortage_rollsBackWholeGroup(CapturedOutput output) {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        // 手工把等待量改小：模拟「台账不变量被破坏」（生产上不该发生，但要能看见）
        jdbcTemplate.update("UPDATE physical_marketing_quota SET waiting_qty = 1 WHERE inv_id = ?", QUOTA_INV_ID);

        assertThatThrownBy(() -> inventoryQuotaService.confirmIssued(
                QUOTA_INV_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 3))
                .as("waiting 不足不是业务失败，是不变量破坏：抛可重试 ⇒ MQ 重投 ⇒ 死信"
                        + "（不复活 INVENTORY_WAITING_SHORTAGE(4312)，不做部分确认）")
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());

        assertThat(claimRowCount(RECORD_ID)).as("整组回滚，确认明细一并消失").isZero();
        var quota = quotaRow();
        assertThat(longOf(quota, "waiting_qty")).as("台账不动").isEqualTo(1L);
        assertThat(longOf(quota, "issued_qty")).isZero();
        assertThat(output.getAll()).as("不变量破坏要人看：error 日志").contains("兑换确认守卫不过");
        assertThat(outboxRowCount()).as("不留半组、不落状态，也不发消息").isZero();
    }

    @Test
    @DisplayName("对账（票 08）：读数瘦成台账三列直读——surplus / waiting / issued，确认返回即在台账两列之间搬完")
    void audit_readsLedgerColumnsDirectly() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        var before = inventoryAuditService.audit(InventorySegmentVO.QUOTA, QUOTA_INV_ID);
        assertThat(before.getInvId()).isEqualTo(QUOTA_INV_ID);
        assertThat(before.getSurplusQty()).as("surplus 直读台账").isEqualTo(QUOTA_SURPLUS - 3);
        assertThat(before.getLedgerWaitingQty()).isEqualTo(3L);
        assertThat(before.getLedgerIssuedQty()).isZero();

        // 确认返回即在两列之间搬完：waiting -2 / issued +2（B6，没有第二步）
        inventoryQuotaService.confirmIssued(QUOTA_INV_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 2);

        var after = inventoryAuditService.audit(InventorySegmentVO.QUOTA, QUOTA_INV_ID);
        assertThat(after.getLedgerWaitingQty()).isEqualTo(1L);
        assertThat(after.getLedgerIssuedQty()).isEqualTo(2L);
        assertThat(after.getSurplusQty()).as("确认不碰 surplus（总量不变）").isEqualTo(QUOTA_SURPLUS - 3);
        // B2：读数本身就是台账三列，守恒等式由这四列直读验证
        assertThat(after.getSurplusQty() + after.getLedgerWaitingQty() + after.getLedgerIssuedQty())
                .isEqualTo(QUOTA_SURPLUS);
    }

    @Test
    @DisplayName("配额不足落兜底：改发兜底虚拟权益，配额台账与配额明细零写入")
    void preDeductWithFallback_leavesQuotaUntouchedWhenShortage() {
        var outcome = preDeductWithFallback(FALLBACK_INV_ID, QUOTA_SURPLUS + 5, RECORD_ID);

        assertThat(outcome).isEqualTo(DeductOutcomeVO.FALLBACK);
        Long fallbackIssued = jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM virtual_inventory_quota WHERE inv_id = ?", Long.class, FALLBACK_INV_ID);
        assertThat(fallbackIssued).as("兜底台账扣了 105").isEqualTo(QUOTA_SURPLUS + 5);
        assertThat(detailCount("virtual_inventory_quota_detail", "inv_id", FALLBACK_INV_ID))
                .as("兜底明细一行").isEqualTo(1);
        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID))
                .as("配额段不落预扣明细").isZero();
        var ledger = quotaRow();
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(QUOTA_SURPLUS);
        assertThat(longOf(ledger, "waiting_qty")).isZero();
        assertThat(longOf(ledger, "issued_qty")).isZero();
    }

    @Test
    @DisplayName("配额足够时走正常预扣：不碰兜底，返回 DEDUCTED")
    void preDeductWithFallback_deductsQuotaWhenAvailable() {
        var outcome = preDeductWithFallback(FALLBACK_INV_ID, 3, RECORD_ID);

        assertThat(outcome).isEqualTo(DeductOutcomeVO.DEDUCTED);
        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID)).isEqualTo(1);
        assertThat(detailCount("virtual_inventory_quota_detail", "inv_id", FALLBACK_INV_ID))
                .as("兜底未动").isZero();
    }

    @Test
    @DisplayName("配额与兜底都不足：返回 FAILED，两段零写入")
    void preDeductWithFallback_failsWhenBothShortage() {
        var outcome = preDeductWithFallback(FALLBACK_INV_ID, FALLBACK_SURPLUS + 1, RECORD_ID);

        assertThat(outcome).isEqualTo(DeductOutcomeVO.FAILED);
        assertThat(detailCount("physical_marketing_quota_detail", "inv_id", QUOTA_INV_ID)).isZero();
        assertThat(detailCount("virtual_inventory_quota_detail", "inv_id", FALLBACK_INV_ID)).isZero();
        assertThat(longOf(quotaRow(), "surplus_qty")).isEqualTo(QUOTA_SURPLUS);
        Long fallbackIssued = jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM virtual_inventory_quota WHERE inv_id = ?", Long.class, FALLBACK_INV_ID);
        assertThat(fallbackIssued).isZero();
    }
}
