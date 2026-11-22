package com.betterschool.marketing.integration.inventory;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryQuotaFlushService;
import com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryQuotaMergeWriter;
import com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryRepository;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 奖品库存守恒判据（award-inventory 两层模型；acceptance.md B1–B4 判据名照抄，口径按 §0 新守恒式）：
 * <ul>
 *   <li><b>B0 分配守恒</b>：池 {@code surplus} 转出量 == 分配流水 {@code SUM(quantity)}（不限量池不减、流水照记）；
 *       撞分配单号按成功回放，池与账户都不再动；</li>
 *   <li><b>B1 零超卖</b>：奖品库存账户 {@code surplus = k} 时并发 N 笔恰好 k 笔成功，终值 0，
 *       不为负由统一台账 {@code UPDATE} 的余量守卫在数据库层面保证，这里锁终值与成功笔数；</li>
 *   <li><b>B2 三列守恒</b>：逐账户 {@code surplus + waiting + issued == 分配转入量}（分配行
 *       {@code flow_type = 0} 不进等式——它只在池与账户 surplus 之间搬运）；</li>
 *   <li><b>B3 明细等式</b>：{@code waiting + issued == SUM(quantity) WHERE flow_type = 1}，
 *       含「探一次再切」分支（{@code k <} 批内笔数时仍相等）；</li>
 *   <li><b>B4 预扣幂等</b>：同 {@code recordId} / {@code bizId} 重放，明细仍 1 行、净扣 1 份；
 *       整批撞 {@code uk_award_biz} ⇒ 扣 0、直接提交、按成功回放；部分撞键 ⇒ 命中行从本次扣减量剔除。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("奖品库存守恒 · B0–B4")
class InventoryQuotaConservationIntegrationTest extends TestingEnvironment {

    private static final String VIRTUAL_POOL_INV_ID = "9801";
    private static final String PHYSICAL_POOL_INV_ID = "9802";
    private static final String VIRTUAL_AWARD_ID = "980101";
    private static final String PHYSICAL_AWARD_ID = "980201";
    private static final long POOL_INITIAL_SURPLUS = 100L;
    private static final AtomicLong RECORD_SEQ = new AtomicLong(98_000_000L);
    private static final AtomicLong ALLOC_SEQ = new AtomicLong(98_500_000L);

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IInventoryQuotaService inventoryQuotaService;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private InventoryQuotaMergeWriter mergeWriter;
    /** 落库真身：B3/B4 断的是「一组一个事务」的 SQL 语义，直接打落库层（合并只是它的调用者之一） */
    @Autowired
    private InventoryQuotaFlushService quotaFlushService;

    @BeforeEach
    void resetFixtures() {
        List.of("virtual_inventory_quota", "physical_marketing_quota",
                "draw_strategy_award_inventory", "draw_strategy_award_inventory_detail",
                "inventory_allocation_flow")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        insertVirtualPool(VIRTUAL_POOL_INV_ID, POOL_INITIAL_SURPLUS);
        insertPhysicalPool(PHYSICAL_POOL_INV_ID, POOL_INITIAL_SURPLUS);
    }

    private void insertVirtualPool(String invId, long surplusQty) {
        jdbcTemplate.update("""
                        INSERT INTO virtual_inventory_quota (inv_id, name, type, value, surplus_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, '守恒用例积分池', 'POINTS', 10, ?, 0, 'open', NOW(), NOW())
                        """, invId, surplusQty);
    }

    private void insertPhysicalPool(String invId, long surplusQty) {
        jdbcTemplate.update("""
                        INSERT INTO physical_marketing_quota (inv_id, name, decr, surplus_qty, waiting_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, '守恒用例配额池', NULL, ?, 0, 0, 'open', NOW(), NOW())
                        """, invId, surplusQty);
    }

    /** 分配（池 → 奖品库存账户），返回分配单号 */
    private String allocate(InventorySegmentVO poolSegment, String poolInvId, String awardId, long quantity) {
        String allocBizId = "ALLOC-" + ALLOC_SEQ.incrementAndGet() + "-" + UUID.randomUUID();
        assertThat(inventoryRepository.allocate(poolSegment, poolInvId, awardId, quantity, allocBizId))
                .as("分配成功").isTrue();
        return allocBizId;
    }

    private long poolSurplusOf(String table, String invId) {
        return jdbcTemplate.queryForObject(
                "SELECT surplus_qty FROM " + table + " WHERE inv_id = ?", Long.class, invId);
    }

    private long surplusOf(String awardId) {
        return jdbcTemplate.queryForObject(
                "SELECT surplus_qty FROM draw_strategy_award_inventory WHERE award_id = ?", Long.class, awardId);
    }

    private long issuedOf(String awardId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM draw_strategy_award_inventory WHERE award_id = ?", Long.class, awardId);
    }

    private long waitingOf(String awardId) {
        return jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM draw_strategy_award_inventory WHERE award_id = ?", Long.class, awardId);
    }

    private long detailCount(String awardId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_strategy_award_inventory_detail WHERE award_id = ?", Long.class, awardId);
        return count == null ? 0 : count;
    }

    private long detailSum(String awardId, int flowType) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM draw_strategy_award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = ?",
                Long.class, awardId, flowType);
        return sum == null ? 0 : sum;
    }

    private long allocateFlowSum(String poolInvId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM inventory_allocation_flow WHERE pool_inv_id = ?",
                Long.class, poolInvId);
        return sum == null ? 0 : sum;
    }

    @Test
    @DisplayName("B0 分配守恒：池 surplus 转出量 == 分配流水 SUM(quantity)；账户无户建户、有户累加")
    void allocateTransfersPoolSurplusToAwardAccount() {
        String allocBizId = allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID, VIRTUAL_AWARD_ID, 30);
        allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID, VIRTUAL_AWARD_ID, 20);

        assertThat(poolSurplusOf("virtual_inventory_quota", VIRTUAL_POOL_INV_ID))
                .as("池转出 50").isEqualTo(POOL_INITIAL_SURPLUS - 50);
        assertThat(surplusOf(VIRTUAL_AWARD_ID)).as("账户转入 50").isEqualTo(50L);
        assertThat(allocateFlowSum(VIRTUAL_POOL_INV_ID)).as("B0：池转出量 == 分配流水").isEqualTo(50L);
        assertThat(detailSum(VIRTUAL_AWARD_ID, 0)).as("奖品侧 AWARD_ALLOCATE 明细与流水同锚").isEqualTo(50L);
        assertThat(allocateFlowCount(allocBizId)).as("两笔分配各一条流水").isEqualTo(2);

        // 实物池 → PHYSICAL 账户（账户类型由池段唯一决定）
        allocate(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID, PHYSICAL_AWARD_ID, 10);
        String accountType = jdbcTemplate.queryForObject(
                "SELECT type FROM draw_strategy_award_inventory WHERE award_id = ?", String.class, PHYSICAL_AWARD_ID);
        assertThat(accountType).as("实物池建户 = PHYSICAL").isEqualTo("PHYSICAL");
        assertThat(poolSurplusOf("physical_marketing_quota", PHYSICAL_POOL_INV_ID)).isEqualTo(90L);
    }

    private long allocateFlowCount(String allocBizId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_allocation_flow WHERE alloc_biz_id = ?", Long.class, allocBizId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("B0 分配幂等：撞分配单号按成功回放——池与账户都不再动；池余量不足返回 false 零副作用")
    void allocate_isIdempotentByAllocBizId_andSideEffectFreeWhenShortage() {
        String allocBizId = "ALLOC-FIXED-" + ALLOC_SEQ.incrementAndGet();
        assertThat(inventoryRepository.allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID,
                VIRTUAL_AWARD_ID, 30, allocBizId)).isTrue();
        long poolAfterFirst = poolSurplusOf("virtual_inventory_quota", VIRTUAL_POOL_INV_ID);
        long accountAfterFirst = surplusOf(VIRTUAL_AWARD_ID);

        // 同分配单号重放：两侧流水撞唯一键 → 提交空事务，池与账户零变化
        assertThat(inventoryRepository.allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID,
                VIRTUAL_AWARD_ID, 30, allocBizId)).as("撞单号按成功回放").isTrue();
        assertThat(poolSurplusOf("virtual_inventory_quota", VIRTUAL_POOL_INV_ID))
                .as("池不再转出").isEqualTo(poolAfterFirst);
        assertThat(surplusOf(VIRTUAL_AWARD_ID)).as("账户不再累加").isEqualTo(accountAfterFirst);
        assertThat(allocateFlowCount(allocBizId)).as("流水仍一行").isEqualTo(1);
        assertThat(detailCount(VIRTUAL_AWARD_ID)).as("明细仍一行").isEqualTo(1);

        // 池余量不足：守卫不过 → 整体回滚（流水一并消失），返回 false
        assertThat(inventoryRepository.allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID,
                VIRTUAL_AWARD_ID, POOL_INITIAL_SURPLUS + 1, "ALLOC-SHORT-" + ALLOC_SEQ.incrementAndGet()))
                .as("确定没转：返回 false").isFalse();
        assertThat(poolSurplusOf("virtual_inventory_quota", VIRTUAL_POOL_INV_ID)).isEqualTo(poolAfterFirst);
        assertThat(allocateFlowSum(VIRTUAL_POOL_INV_ID)).as("失败分配不留流水").isEqualTo(50L);
    }

    @Test
    @DisplayName("B1 零超卖：账户 surplus = 20 时并发 50 笔恰好 20 笔成功，终值 0，明细 20 行")
    void noOversellUnderConcurrency() throws Exception {
        allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID, VIRTUAL_AWARD_ID, 20);
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET surplus_qty = 20 WHERE award_id = ?",
                VIRTUAL_AWARD_ID);

        int concurrent = 50;
        ExecutorService pool = Executors.newFixedThreadPool(concurrent);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrent; i++) {
                String recordId = String.valueOf(RECORD_SEQ.incrementAndGet());
                futures.add(pool.submit((Callable<Boolean>) () -> {
                    start.await();
                    return deductUntilSettled(recordId);
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS))) {
                    succeeded++;
                }
            }

            assertThat(succeeded).as("恰好 stock 笔成功（重放不重复计：recordId 唯一）").isEqualTo(20);
            assertThat(surplusOf(VIRTUAL_AWARD_ID)).as("终值为 0").isZero();
            assertThat(issuedOf(VIRTUAL_AWARD_ID)).isEqualTo(20L);
            assertThat(detailSum(VIRTUAL_AWARD_ID, 1)).isEqualTo(20L);
            // B2：守恒到 0（分配转入量 20 = surplus + issued）
            assertThat(surplusOf(VIRTUAL_AWARD_ID) + issuedOf(VIRTUAL_AWARD_ID)).isEqualTo(20L);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 按三出口的调用方契约驱动一笔预扣：{@code RETRY_LATER} 不算失败也不算成功——
     * **不切兜底、凭同一 {@code recordId} 重放**（{@code recordId} 由「用户 + token」确定性映射），
     * 唯一键兜底不双扣；重放撞键按成功回放。只重试「库存处理中」这条可重试出口，
     * 参数错等业务异常原样抛出。
     */
    private boolean deductUntilSettled(String recordId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return inventoryQuotaService.preDeduct(AwardInventoryType.VIRTUAL, VIRTUAL_AWARD_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, recordId);
            } catch (AppException retryable) {
                assertThat(retryable.getCode())
                        .as("只有 SYSTEM_ERROR（999）是可重试出口，其余异常原样上抛")
                        .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());
                // RETRY_LATER：还在处理中或已回滚，等一下再重放
                Thread.sleep(20);
            }
        }
        throw new AssertionError("预扣重放 100 次仍未落定 recordId=" + recordId);
    }

    @Test
    @DisplayName("B2/B3 三列守恒与明细等式：一半成功一半不足，surplus + waiting + issued == 分配转入量")
    void ledgerSumEqualsAllocatedSnapshot() {
        int stock = 10;
        allocate(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID, PHYSICAL_AWARD_ID, stock);

        for (int i = 0; i < 20; i++) {
            inventoryQuotaService.preDeduct(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                    InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet()));
        }
        // 读数前等合并器排空：B2 / B3 是台账断言，不能带在途 flush
        assertThat(mergeWriter.awaitIdle(5000))
                .as("合并器已排空").isTrue();
        assertThat(mergeWriter.pending()).isZero();

        long surplus = surplusOf(PHYSICAL_AWARD_ID);
        long waiting = waitingOf(PHYSICAL_AWARD_ID);
        long issued = issuedOf(PHYSICAL_AWARD_ID);
        assertThat(surplus).isZero();
        assertThat(waiting).isEqualTo(stock);
        // B2：surplus + waiting + issued == 分配转入量（切换前快照）
        assertThat(surplus + waiting + issued).isEqualTo(stock);
        // B3：明细等式（分配行 flow_type = 0 不进等式）
        assertThat(waiting + issued).isEqualTo(detailSum(PHYSICAL_AWARD_ID, 1));
        assertThat(detailCount(PHYSICAL_AWARD_ID)).as("预扣 10 行 + 分配 1 行").isEqualTo(stock + 1);
    }

    @Test
    @DisplayName("B3 探一次再切：账户 surplus=2 时一组 5 笔，前 2 笔成功其余 INSUFFICIENT，明细等式仍成立")
    void detailSumEqualsWaitingPlusIssued_partialSuccess() {
        allocate(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID, PHYSICAL_AWARD_ID, 2);
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET surplus_qty = 2 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);

        List<InventoryDeductRequestVO> batch = LongStream.range(0, 5)
                .mapToObj(i -> new InventoryDeductRequestVO(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())))
                .toList();

        List<DeductResult> results =
                quotaFlushService.flushDeductGroup(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, batch);

        assertThat(results).as("逐件结果，前 k 笔成功").hasSize(5);
        assertThat(results.subList(0, 2)).containsExactly(DeductResult.DEDUCTED, DeductResult.DEDUCTED);
        assertThat(results.subList(2, 5)).containsExactly(DeductResult.INSUFFICIENT, DeductResult.INSUFFICIENT,
                DeductResult.INSUFFICIENT);

        // 第二事务路径必须让明细与台账仍然平：插进去的是 k 行不是 N 行
        assertThat(detailCount(PHYSICAL_AWARD_ID)).as("只有分配 1 行 + 预扣 k=2 行").isEqualTo(3);
        assertThat(waitingOf(PHYSICAL_AWARD_ID)).isEqualTo(2);
        assertThat(surplusOf(PHYSICAL_AWARD_ID)).isZero();
        // B3：waiting + issued == SUM(flow_type = 1)，部分成功时仍相等
        assertThat(waitingOf(PHYSICAL_AWARD_ID) + issuedOf(PHYSICAL_AWARD_ID))
                .isEqualTo(detailSum(PHYSICAL_AWARD_ID, 1));
        // B2：三列守恒（对分配转入量 2）
        assertThat(surplusOf(PHYSICAL_AWARD_ID) + waitingOf(PHYSICAL_AWARD_ID)
                + issuedOf(PHYSICAL_AWARD_ID)).isEqualTo(2L);
    }

    @Test
    @DisplayName("B4 整批撞 uk：扣 0、直接提交、按成功回放，明细仍 1 行、净扣 1 份")
    void wholeBatchDuplicate_replaysAsSuccessWithoutSecondDeduction() {
        allocate(InventorySegmentVO.VIRTUAL, VIRTUAL_POOL_INV_ID, VIRTUAL_AWARD_ID, POOL_INITIAL_SURPLUS);
        String recordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        assertThat(inventoryQuotaService.preDeduct(AwardInventoryType.VIRTUAL, VIRTUAL_AWARD_ID, 3,
                InventoryBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        long issuedAfterFirst = issuedOf(VIRTUAL_AWARD_ID);

        // 同 bizId 再走一次落库方法（重放）：整批撞 uk ⇒ 扣 0、提交、按成功回放
        List<InventoryDeductRequestVO> replay = List.of(new InventoryDeductRequestVO(
                AwardInventoryType.VIRTUAL, VIRTUAL_AWARD_ID, 3, InventoryBizTypes.LOTTERY_DRAW, recordId));
        List<DeductResult> results =
                quotaFlushService.flushDeductGroup(AwardInventoryType.VIRTUAL, VIRTUAL_AWARD_ID, replay);

        assertThat(results).containsExactly(DeductResult.DEDUCTED);
        assertThat(detailCount(VIRTUAL_AWARD_ID)).as("分配 1 行 + 预扣仍 1 行").isEqualTo(2);
        assertThat(issuedOf(VIRTUAL_AWARD_ID))
                .as("净扣 1 份").isEqualTo(issuedAfterFirst);
        assertThat(surplusOf(VIRTUAL_AWARD_ID)).isEqualTo(POOL_INITIAL_SURPLUS - 3);
    }

    @Test
    @DisplayName("B4 部分撞键：回查命中行并从本次扣减量剔除，其余照扣；主档与兜底两账户互不撞键")
    void partialDuplicate_excludesExistingFromDeduction() {
        allocate(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID, PHYSICAL_AWARD_ID, POOL_INITIAL_SURPLUS);
        String duplicateRecordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        assertThat(inventoryQuotaService.preDeduct(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 2,
                InventoryBizTypes.LOTTERY_DRAW, duplicateRecordId)).isTrue();
        long waitingAfterFirst = waitingOf(PHYSICAL_AWARD_ID);

        // 一组三笔：第一笔是重放，后两笔是新扣
        List<InventoryDeductRequestVO> batch = List.of(
                new InventoryDeductRequestVO(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 2,
                        InventoryBizTypes.LOTTERY_DRAW, duplicateRecordId),
                new InventoryDeductRequestVO(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())),
                new InventoryDeductRequestVO(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())));

        List<DeductResult> results =
                quotaFlushService.flushDeductGroup(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, batch);

        assertThat(results).containsExactly(DeductResult.DEDUCTED, DeductResult.DEDUCTED, DeductResult.DEDUCTED);
        assertThat(detailCount(PHYSICAL_AWARD_ID)).as("分配 1 行 + 预扣 3 行").isEqualTo(4);
        assertThat(waitingOf(PHYSICAL_AWARD_ID)).as("重放件的量被剔除，只扣新件的 2 份")
                .isEqualTo(waitingAfterFirst + 2);
        assertThat(surplusOf(PHYSICAL_AWARD_ID)).isEqualTo(POOL_INITIAL_SURPLUS - 4);
        // B3 在部分撞键下仍成立
        assertThat(waitingOf(PHYSICAL_AWARD_ID) + issuedOf(PHYSICAL_AWARD_ID))
                .isEqualTo(detailSum(PHYSICAL_AWARD_ID, 1));
    }

    @Test
    @DisplayName("扣不动 ⇒ 第一事务整体回滚不留明细残痕；恢复余量后凭同一 bizId 重试，唯一键兜底不双扣")
    void transactionFailure_rollsBackAndRetryDoesNotDoubleDeduct() {
        allocate(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID, PHYSICAL_AWARD_ID, POOL_INITIAL_SURPLUS);
        String recordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        // 余量拉成 0：一组一笔也扣不动，第一事务（含明细 INSERT）整体回滚
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET surplus_qty = 0 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);
        List<InventoryDeductRequestVO> batch = List.of(new InventoryDeductRequestVO(
                AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1, InventoryBizTypes.LOTTERY_DRAW, recordId));
        assertThat(quotaFlushService.flushDeductGroup(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, batch))
                .as("确定没扣：全组 INSUFFICIENT，不落明细")
                .containsExactly(DeductResult.INSUFFICIENT);
        assertThat(detailSum(PHYSICAL_AWARD_ID, 1))
                .as("第一事务已回滚，预扣明细不留残痕").isZero();

        // 恢复余量后凭同一 bizId 重试：正常扣一份（唯一键兜底，不会双扣）
        jdbcTemplate.update("UPDATE draw_strategy_award_inventory SET surplus_qty = 5 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);
        assertThat(inventoryQuotaService.preDeduct(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                InventoryBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        assertThat(detailSum(PHYSICAL_AWARD_ID, 1)).isEqualTo(1L);
        assertThat(waitingOf(PHYSICAL_AWARD_ID)).isEqualTo(1L);
    }
}
