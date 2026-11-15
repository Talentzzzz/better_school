package com.betterschool.marketing.integration.inventory;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.service.IInventoryAuditService;
import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryRepository;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实物奖品库存两段式集成用例（award-inventory 两层模型，票 06 / 07 口径搬到统一台账）：
 * 先分配建户（池 surplus → 账户 surplus），预扣与兑换确认都进进程内合并器，**返回即台账已动**——
 * 预扣 {@code surplus -= N, waiting += N}（B2 / B3 / B4）；确认 {@code waiting -= c, issued += c}、
 * {@code flow_type = 2} 明细恰好 c 行，没有第二步（B6）。{@code waiting} 不足 = 不变量破坏：
 * 整组回滚（连确认明细一起）+ error 日志 + 抛可重试（B5，不复活 4312、不做部分确认）。
 * 账户不足落兜底奖品位（主档零写入）。池侧只被分配扣减（守恒靠分配流水表）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("库存两段式 · 实物奖品库存账户")
class PhysicalQuotaIntegrationTest extends TestingEnvironment {

    private static final String PHYSICAL_POOL_INV_ID = "9101";
    /** 主档实物奖品（奖品位 id = 奖品库存账户维度） */
    private static final String PHYSICAL_AWARD_ID = "910101";
    /** 兜底虚拟奖品（积分），分配 999 份 */
    private static final String FALLBACK_AWARD_ID = "910201";
    private static final String VIRTUAL_POOL_INV_ID = "9103";
    private static final long POOL_SURPLUS = 100L;
    private static final long FALLBACK_ALLOCATED = 999L;
    /** 抽奖记录 id（明细 biz_id）：同一笔业务重复预扣靠 uk_award_biz 去重 */
    private static final String RECORD_ID = "95001";

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
    @Autowired
    private InventoryRepository inventoryRepository;

    @BeforeEach
    void resetFixtures() {
        List.of("virtual_inventory_quota", "physical_marketing_quota",
                "draw_strategy_award_inventory", "draw_strategy_award_inventory_detail",
                "inventory_allocation_flow", "outbox_message")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        jdbcTemplate.update("""
                        INSERT INTO physical_marketing_quota (inv_id, name, decr, surplus_qty, waiting_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, '暑期实物投放池', NULL, ?, 0, 0, 'open', NOW(), NOW())
                        """,
                PHYSICAL_POOL_INV_ID, POOL_SURPLUS);
        jdbcTemplate.update("""
                        INSERT INTO virtual_inventory_quota (inv_id, name, type, value, surplus_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, '兜底积分池', 'POINTS', 10, ?, 0, 'open', NOW(), NOW())
                        """,
                VIRTUAL_POOL_INV_ID, FALLBACK_ALLOCATED);
        // 先分配建户：抽奖只扣奖品库存账户；池 surplus 转出后即由分配流水对账
        assertThat(inventoryRepository.allocate(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID,
                PHYSICAL_AWARD_ID, POOL_SURPLUS, "ALLOC-" + UUID.randomUUID())).isTrue();
        assertThat(inventoryRepository.allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID,
                FALLBACK_AWARD_ID, FALLBACK_ALLOCATED, "ALLOC-" + UUID.randomUUID())).isTrue();
    }

    private boolean preDeduct(long quantity, String recordId) {
        return inventoryQuotaService.preDeduct(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
    }

    private DeductOutcomeVO preDeductWithFallback(String fallbackAwardId, long quantity, String recordId) {
        InventoryDeductRequestVO primary = new InventoryDeductRequestVO(AwardInventoryType.PHYSICAL,
                PHYSICAL_AWARD_ID, quantity, InventoryBizTypes.LOTTERY_DRAW, recordId);
        InventoryDeductRequestVO fallback = fallbackAwardId == null ? null
                : new InventoryDeductRequestVO(AwardInventoryType.VIRTUAL, fallbackAwardId, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
        return inventoryQuotaService.preDeductWithFallback(primary, fallback);
    }

    private Map<String, Object> accountRow() {
        return jdbcTemplate.queryForMap(
                "SELECT surplus_qty, waiting_qty, issued_qty, state FROM draw_strategy_award_inventory"
                        + " WHERE award_id = ?", PHYSICAL_AWARD_ID);
    }

    private long longOf(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    /** 该记录的兑换确认行（flow_type = 2）：取不到即断言失败。确认返回即已提交 */
    private Map<String, Object> claimRow(String recordId) {
        return jdbcTemplate.queryForMap("""
                        SELECT flow_type, biz_type, quantity
                        FROM draw_strategy_award_inventory_detail
                        WHERE award_id = ? AND flow_type = ? AND biz_id = ?
                        """,
                PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_CLAIM, recordId);
    }

    private int claimRowCount(String recordId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM draw_strategy_award_inventory_detail
                        WHERE award_id = ? AND flow_type = ? AND biz_id = ?
                        """,
                Integer.class, PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_CLAIM, recordId);
        return count == null ? 0 : count;
    }

    /** 预扣 / 确认都不该写 outbox（合并消息已随票 08 退休）：全表应为 0 行 */
    private int outboxRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("分配建户：池 surplus 转出、账户 surplus 转入，两侧流水同锚分配单号")
    void allocate_createsAccountAndTransfers() {
        var account = accountRow();
        assertThat(longOf(account, "surplus_qty")).as("账户转入 = 池投放量").isEqualTo(POOL_SURPLUS);
        assertThat(account.get("type")).as("实物池建户 = PHYSICAL").isEqualTo("PHYSICAL");
        assertThat(account.get("state")).isEqualTo(0);

        Long poolSurplus = jdbcTemplate.queryForObject(
                "SELECT surplus_qty FROM physical_marketing_quota WHERE inv_id = ?",
                Long.class, PHYSICAL_POOL_INV_ID);
        assertThat(poolSurplus).as("池 surplus 已全部转出").isZero();
        Long flowSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM inventory_allocation_flow WHERE pool_inv_id = ?",
                Long.class, PHYSICAL_POOL_INV_ID);
        assertThat(flowSum).as("池转出量 == 分配流水").isEqualTo(POOL_SURPLUS);
        Long allocateDetailSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM draw_strategy_award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = 0",
                Long.class, PHYSICAL_AWARD_ID);
        assertThat(allocateDetailSum).as("奖品侧 AWARD_ALLOCATE 明细同量").isEqualTo(POOL_SURPLUS);
    }

    @Test
    @DisplayName("预扣返回即台账已动：surplus -= N、waiting += N、明细一行 flow_type=1，三列守恒")
    void preDeduct_commitsWaitingImmediately() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        var ledger = accountRow();
        assertThat(longOf(ledger, "surplus_qty")).as("surplus 已减（没有第二步）")
                .isEqualTo(POOL_SURPLUS - 3);
        assertThat(longOf(ledger, "waiting_qty")).as("waiting 已增").isEqualTo(3L);
        assertThat(longOf(ledger, "issued_qty")).isZero();
        assertThat(((Number) ledger.get("state")).intValue()).as("仍有余量，state 保持 open").isZero();

        Map<String, Object> detail = jdbcTemplate.queryForMap(
                "SELECT flow_type, biz_type, biz_id, quantity FROM draw_strategy_award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = 1",
                PHYSICAL_AWARD_ID);
        assertThat(detail.get("biz_type")).as("业务类型 = 抽奖预扣").isEqualTo("LOTTERY_DRAW");
        assertThat((String) detail.get("biz_id")).as("业务 ID = draw_record.record_id")
                .isEqualTo(RECORD_ID);
        assertThat(((Number) detail.get("quantity")).longValue()).isEqualTo(3L);

        // B2 三列守恒：分配转入量 100 = surplus + waiting + issued（分配行不进明细等式）
        assertThat(longOf(ledger, "surplus_qty") + longOf(ledger, "waiting_qty") + longOf(ledger, "issued_qty"))
                .isEqualTo(POOL_SURPLUS);
        // B3 明细等式：waiting + issued == SUM(quantity) WHERE flow_type = 1
        assertThat(longOf(ledger, "waiting_qty") + longOf(ledger, "issued_qty"))
                .isEqualTo(detailSumOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT));
    }

    private long detailSumOf(String awardId, int flowType) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM draw_strategy_award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = ?",
                Long.class, awardId, flowType);
        return sum == null ? 0 : sum;
    }

    private long detailCountOf(String awardId, int flowType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_strategy_award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = ?",
                Long.class, awardId, flowType);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("重复预扣同一 recordId：撞 uk_award_biz 按成功回放，明细仍一行、净扣一份")
    void preDeduct_sameRecordId_replaysWithoutSecondDeduction() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(preDeduct(3, RECORD_ID))
                .as("第二次撞业务唯一键，按成功回放").isTrue();

        assertThat(detailCountOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT))
                .as("预扣明细只有一行").isEqualTo(1);
        var ledger = accountRow();
        assertThat(longOf(ledger, "waiting_qty")).as("净扣一份").isEqualTo(3L);
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(POOL_SURPLUS - 3);
    }

    @Test
    @DisplayName("账户 exhausted 后重试同一 recordId：state 检查不吞掉重放")
    void preDeduct_sameRecordId_afterExhausted_replays() {
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET surplus_qty = 3 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        assertThat(((Number) accountRow().get("state")).intValue()).as("surplus 恰好扣空 → exhausted").isEqualTo(1);

        assertThat(preDeduct(3, RECORD_ID))
                .as("台账非 open 但明细已存在 → 成功回放").isTrue();

        assertThat(detailCountOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT)).isEqualTo(1);
        assertThat(longOf(accountRow(), "waiting_qty")).as("重放不再扣账").isEqualTo(3L);
    }

    @Test
    @DisplayName("主档余量被上一笔扣空后重试同一 recordId：撞键按成功回放，不落兜底、净扣一份")
    void preDeductWithFallback_sameRecordId_afterShortage_replaysWithoutFallback() {
        // 主档只剩 1 份：模拟「最后一单位被同 token 的上一笔取走」
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET surplus_qty = 1 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);

        assertThat(preDeductWithFallback(FALLBACK_AWARD_ID, 1, RECORD_ID))
                .isEqualTo(DeductOutcomeVO.DEDUCTED);
        assertThat(longOf(accountRow(), "surplus_qty")).isZero();

        assertThat(preDeductWithFallback(FALLBACK_AWARD_ID, 1, RECORD_ID))
                .as("主档已空，但明细已存在 → 撞键按成功回放，不能把同一笔再扣到兜底账户")
                .isEqualTo(DeductOutcomeVO.DEDUCTED);

        assertThat(longOf(accountRow(), "waiting_qty")).as("净扣一份").isEqualTo(1L);
        assertThat(detailCountOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT)).isEqualTo(1);
        assertThat(detailCountOf(FALLBACK_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT)).isZero();
    }

    @Test
    @DisplayName("上一笔已走兜底：主档仍不足时直接回放 FALLBACK，不再扣第二次")
    void preDeductWithFallback_sameRecordId_whenFallbackAlreadyUsed_replays() {
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET surplus_qty = 0 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);

        assertThat(preDeductWithFallback(FALLBACK_AWARD_ID, 1, RECORD_ID))
                .isEqualTo(DeductOutcomeVO.FALLBACK);
        assertThat(preDeductWithFallback(FALLBACK_AWARD_ID, 1, RECORD_ID))
                .as("兜底账户明细已存在，主档扣不出时直接回放").isEqualTo(DeductOutcomeVO.FALLBACK);

        assertThat(detailCountOf(FALLBACK_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT))
                .as("兜底只扣一次").isEqualTo(1);
        Long fallbackIssued = jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM draw_strategy_award_inventory WHERE award_id = ?",
                Long.class, FALLBACK_AWARD_ID);
        assertThat(fallbackIssued).isEqualTo(1L);
        assertThat(detailCountOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT)).isZero();
    }

    @Test
    @DisplayName("兑换确认（B6）：返回即转移已提交——waiting -= c、issued += c、flow_type=2 明细恰好 c 行，无第二步")
    void confirmIssued_transfersImmediately() {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();

        inventoryQuotaService.confirmIssued(PHYSICAL_AWARD_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 3);

        Map<String, Object> claim = claimRow(RECORD_ID);
        assertThat(((Number) claim.get("flow_type")).intValue())
                .as("兑换确认 flow_type=2").isEqualTo(Constants.DETAIL_TYPE_CLAIM);
        assertThat(claim.get("biz_type")).as("业务类型 = 兑换确认").isEqualTo(InventoryBizTypes.LOTTERY_CLAIM);
        assertThat(((Number) claim.get("quantity")).longValue()).isEqualTo(3L);
        var account = accountRow();
        assertThat(longOf(account, "waiting_qty")).as("确认返回即 waiting 转走（没有第二步）").isZero();
        assertThat(longOf(account, "issued_qty")).as("确认返回即计入 issued").isEqualTo(3L);
        assertThat(longOf(account, "surplus_qty")).as("预扣已扣过 surplus，确认不再扣（总量不变）")
                .isEqualTo(POOL_SURPLUS - 3);
        assertThat(outboxRowCount()).as("确认不再发合并消息").isZero();

        // B2 三列守恒：分配转入量 100 = surplus + waiting + issued
        assertThat(longOf(account, "surplus_qty") + longOf(account, "waiting_qty") + longOf(account, "issued_qty"))
                .isEqualTo(POOL_SURPLUS);
    }

    @Test
    @DisplayName("重复确认同一 recordId：撞 uk_award_biz 按成功回放——明细仍一行、转移只发生一次")
    void confirmIssued_sameRecordId_replaysWithoutSecondTransfer() {
        assertThat(preDeduct(1, RECORD_ID)).isTrue();

        inventoryQuotaService.confirmIssued(PHYSICAL_AWARD_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 1);
        inventoryQuotaService.confirmIssued(PHYSICAL_AWARD_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 1);

        assertThat(claimRowCount(RECORD_ID)).as("确认明细只有一行").isEqualTo(1);
        var account = accountRow();
        assertThat(longOf(account, "issued_qty")).as("转移只发生一次（第二笔撞键回放，不再转）").isEqualTo(1L);
        assertThat(longOf(account, "waiting_qty")).isZero();
        assertThat(outboxRowCount()).as("不发合并消息").isZero();
    }

    @Test
    @DisplayName("没有预扣行直接确认（账不平）：抛可重试异常，不落确认行、台账不动")
    void confirmIssued_withoutDeductRow_throwsRetryableWithZeroSideEffects() {
        assertThatThrownBy(() -> inventoryQuotaService.confirmIssued(
                PHYSICAL_AWARD_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 1))
                .as("waiting 守卫不过 = 不变量破坏，抛可重试（不再查预扣行、不回 INVENTORY_PARAM_ERROR）")
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());

        assertThat(claimRowCount(RECORD_ID)).as("整组回滚，不落确认行").isZero();
        assertThat(outboxRowCount()).as("不发消息").isZero();
        assertThat(longOf(accountRow(), "waiting_qty")).isZero();
        assertThat(longOf(accountRow(), "issued_qty")).isZero();
    }

    @Test
    @DisplayName("waiting 不足（B5）：整组回滚——确认明细一并消失、台账不动、error 日志、抛可重试")
    void claimWaitingShortage_rollsBackWholeGroup(CapturedOutput output) {
        assertThat(preDeduct(3, RECORD_ID)).isTrue();
        // 手工把等待量改小：模拟「台账不变量被破坏」（生产上不该发生，但要能看见）
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET waiting_qty = 1 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);

        assertThatThrownBy(() -> inventoryQuotaService.confirmIssued(
                PHYSICAL_AWARD_ID, InventoryBizTypes.LOTTERY_CLAIM, RECORD_ID, 3))
                .as("waiting 不足不是业务失败，是不变量破坏：抛可重试 ⇒ MQ 重投 ⇒ 死信"
                        + "（不复活 INVENTORY_WAITING_SHORTAGE(4312)，不做部分确认）")
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());

        assertThat(claimRowCount(RECORD_ID)).as("整组回滚，确认明细一并消失").isZero();
        var account = accountRow();
        assertThat(longOf(account, "waiting_qty")).as("台账不动").isEqualTo(1L);
        assertThat(longOf(account, "issued_qty")).isZero();
        assertThat(output.getAll()).as("不变量破坏要人看：error 日志").contains("兑换确认守卫不过");
        assertThat(outboxRowCount()).as("不留半组、不落状态，也不发消息").isZero();
    }

    @Test
    @DisplayName("对账（票 08）：池侧读数 = 分配后的池三列；分配守恒 = 池转出量 == 分配流水")
    void audit_readsPoolAfterAllocation() {
        // 分配 30 份出去：池三列直读（surplus 70 / waiting 0 / issued 0）
        var before = inventoryAuditService.audit(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID);
        assertThat(before.getInvId()).isEqualTo(PHYSICAL_POOL_INV_ID);
        assertThat(before.getSurplusQty()).as("池 surplus 被分配转出").isEqualTo(POOL_SURPLUS - 30);
        assertThat(before.getLedgerWaitingQty()).isZero();
        assertThat(before.getLedgerIssuedQty()).isZero();

        Long flowSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM inventory_allocation_flow WHERE pool_inv_id = ?",
                Long.class, PHYSICAL_POOL_INV_ID);
        assertThat(POOL_SURPLUS - before.getSurplusQty()).as("池转出量 == 分配流水（池侧守恒口径）").isEqualTo(flowSum);
    }

    @Test
    @DisplayName("账户不足落兜底：改发兜底虚拟奖品，主档账户零写入")
    void preDeductWithFallback_leavesPrimaryUntouchedWhenShortage() {
        var outcome = preDeductWithFallback(FALLBACK_AWARD_ID, POOL_SURPLUS + 5, RECORD_ID);

        assertThat(outcome).isEqualTo(DeductOutcomeVO.FALLBACK);
        Long fallbackIssued = jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM draw_strategy_award_inventory WHERE award_id = ?",
                Long.class, FALLBACK_AWARD_ID);
        assertThat(fallbackIssued).as("兜底账户扣了 105").isEqualTo(POOL_SURPLUS + 5);
        assertThat(detailCountOf(FALLBACK_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT))
                .as("兜底明细一行").isEqualTo(1);
        assertThat(detailCountOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT))
                .as("主档不落预扣明细").isZero();
        var ledger = accountRow();
        assertThat(longOf(ledger, "surplus_qty")).isEqualTo(POOL_SURPLUS);
        assertThat(longOf(ledger, "waiting_qty")).isZero();
        assertThat(longOf(ledger, "issued_qty")).isZero();
    }

    @Test
    @DisplayName("账户足够时走正常预扣：不碰兜底，返回 DEDUCTED")
    void preDeductWithFallback_deductsPrimaryWhenAvailable() {
        var outcome = preDeductWithFallback(FALLBACK_AWARD_ID, 3, RECORD_ID);

        assertThat(outcome).isEqualTo(DeductOutcomeVO.DEDUCTED);
        assertThat(detailCountOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT)).isEqualTo(1);
        assertThat(detailCountOf(FALLBACK_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT))
                .as("兜底未动").isZero();
    }

    @Test
    @DisplayName("主档与兜底都不足：返回 FAILED，两边零写入")
    void preDeductWithFallback_failsWhenBothShortage() {
        var outcome = preDeductWithFallback(FALLBACK_AWARD_ID, FALLBACK_ALLOCATED + 1, RECORD_ID);

        assertThat(outcome).isEqualTo(DeductOutcomeVO.FAILED);
        assertThat(detailCountOf(PHYSICAL_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT)).isZero();
        assertThat(detailCountOf(FALLBACK_AWARD_ID, Constants.DETAIL_TYPE_DEDUCT)).isZero();
        assertThat(longOf(accountRow(), "surplus_qty")).isEqualTo(POOL_SURPLUS);
        Long fallbackIssued = jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM draw_strategy_award_inventory WHERE award_id = ?",
                Long.class, FALLBACK_AWARD_ID);
        assertThat(fallbackIssued).isZero();
    }
}
