package com.betterschool.marketing.integration.inventory;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryRepository;
import com.betterschool.marketing.infrastructure.inventory.merge.QuotaMergeBuffer;
import com.betterschool.marketing.integration.support.InventoryMergeTestSupport;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
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
import java.util.Map;
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
 * 库存守恒判据（acceptance.md B1–B4，判据名照抄）：
 * <ul>
 *   <li><b>B1 零超卖</b>：{@code surplus = k} 时并发 N 笔恰好 k 笔成功，终值 0，任何时刻不为负
 *       ——不为负由台账 {@code UPDATE} 的余量守卫在数据库层面保证，这里锁终值与成功笔数；</li>
 *   <li><b>B2 三列守恒</b>：逐 inv {@code surplus + waiting + issued == 初值}；</li>
 *   <li><b>B3 明细等式</b>：{@code waiting + issued == SUM(quantity) WHERE type = 1}，
 *       含「探一次再切」分支（{@code k <} 批内笔数时仍相等）；</li>
 *   <li><b>B4 预扣幂等</b>：同 {@code recordId} / {@code bizId} 重放，明细仍 1 行、净扣 1 份；
 *       整批撞 {@code uk_biz_type} ⇒ 扣 0、直接提交、按成功回放；部分撞键 ⇒ 命中行从本次扣减量剔除。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("库存守恒 · B1–B4")
class InventoryQuotaConservationIntegrationTest extends TestingEnvironment {

    private static final String VIRTUAL_INV_ID = "9801";
    private static final String QUOTA_INV_ID = "9802";
    private static final long INITIAL_SURPLUS = 100L;
    private static final AtomicLong RECORD_SEQ = new AtomicLong(98_000_000L);

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
    private QuotaMergeBuffer quotaMergeBuffer;

    @BeforeEach
    void resetFixtures() {
        List.of("virtual_inventory_quota", "virtual_inventory_quota_detail",
                "physical_marketing_quota", "physical_marketing_quota_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        insertVirtualLedger(VIRTUAL_INV_ID, INITIAL_SURPLUS);
        insertQuotaLedger(QUOTA_INV_ID, INITIAL_SURPLUS);
    }

    private void insertVirtualLedger(String invId, long surplusQty) {
        jdbcTemplate.update("""
                        INSERT INTO virtual_inventory_quota (inv_id, name, type, value, surplus_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, '守恒用例积分池', 'POINTS', 10, ?, 0, 'open', NOW(), NOW())
                        """, invId, surplusQty);
    }

    private void insertQuotaLedger(String invId, long surplusQty) {
        jdbcTemplate.update("""
                        INSERT INTO physical_marketing_quota (inv_id, name, decr, surplus_qty, waiting_qty, issued_qty,
                                                              state, create_time, update_time)
                        VALUES (?, '守恒用例配额', NULL, ?, 0, 0, 'open', NOW(), NOW())
                        """, invId, surplusQty);
    }

    private long surplusOf(String table, String invId) {
        return jdbcTemplate.queryForObject(
                "SELECT surplus_qty FROM " + table + " WHERE inv_id = ?", Long.class, invId);
    }

    private long issuedOf(String table, String invId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM " + table + " WHERE inv_id = ?", Long.class, invId);
    }

    private long waitingOf(String invId) {
        return jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM physical_marketing_quota WHERE inv_id = ?", Long.class, invId);
    }

    private long detailCount(String table, String invId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE inv_id = ?", Long.class, invId);
        return count == null ? 0 : count;
    }

    private long detailSum(String table, String invId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM " + table + " WHERE inv_id = ? AND type = 1",
                Long.class, invId);
        return sum == null ? 0 : sum;
    }

    @Test
    @DisplayName("B1 零超卖：surplus = 20 时并发 50 笔恰好 20 笔成功，终值 0，明细 20 行")
    void noOversellUnderConcurrency() throws Exception {
        int concurrent = 50;
        int stock = 20;
        jdbcTemplate.update("UPDATE virtual_inventory_quota SET surplus_qty = ? WHERE inv_id = ?",
                stock, VIRTUAL_INV_ID);

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

            assertThat(succeeded).as("恰好 stock 笔成功（重放不重复计：recordId 唯一）").isEqualTo(stock);
            assertThat(surplusOf("virtual_inventory_quota", VIRTUAL_INV_ID)).as("终值为 0").isZero();
            assertThat(issuedOf("virtual_inventory_quota", VIRTUAL_INV_ID)).isEqualTo(stock);
            assertThat(detailCount("virtual_inventory_quota_detail", VIRTUAL_INV_ID)).isEqualTo(stock);
            // B2：守恒到 0
            assertThat(surplusOf("virtual_inventory_quota", VIRTUAL_INV_ID)
                    + issuedOf("virtual_inventory_quota", VIRTUAL_INV_ID)).isEqualTo(stock);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 按三出口的调用方契约驱动一笔预扣：{@code TIMEOUT_UNKNOWN} / {@code FAILED} 不算失败也不算成功——
     * **不切兜底、凭同一 {@code recordId} 重放**（{@code recordId} 由「用户 + token」确定性映射），
     * 唯一键兜底不双扣；重放撞键按成功回放。只重试「库存处理中」这条可重试出口，
     * 参数错等业务异常原样抛出。
     */
    private boolean deductUntilSettled(String recordId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return inventoryQuotaService.preDeduct(InventorySegmentVO.VIRTUAL, VIRTUAL_INV_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, recordId);
            } catch (AppException retryable) {
                assertThat(retryable.getCode())
                        .as("只有 SYSTEM_ERROR（999）是可重试出口，其余异常原样上抛")
                        .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());
                // TIMEOUT_UNKNOWN / FAILED：还在缓冲或落库中，等一下再重放
                Thread.sleep(20);
            }
        }
        throw new AssertionError("预扣重放 100 次仍未落定 recordId=" + recordId);
    }

    @Test
    @DisplayName("B2/B3 三列守恒与明细等式：一半成功一半不足，surplus + waiting + issued == 初值")
    void ledgerSumEqualsInitialSnapshot() {
        int stock = 10;
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = ? WHERE inv_id = ?",
                stock, QUOTA_INV_ID);

        for (int i = 0; i < 20; i++) {
            inventoryQuotaService.preDeduct(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1,
                    InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet()));
        }
        // 读数前等合并器排空（票 08 改写的夹具）：B2 / B3 是台账断言，不能带在途 flush
        assertThat(InventoryMergeTestSupport.awaitDrained(quotaMergeBuffer, 5000))
                .as("合并器已排空").isTrue();
        assertThat(InventoryMergeTestSupport.pendingOf(quotaMergeBuffer)).isZero();

        long surplus = surplusOf("physical_marketing_quota", QUOTA_INV_ID);
        long waiting = waitingOf(QUOTA_INV_ID);
        long issued = issuedOf("physical_marketing_quota", QUOTA_INV_ID);
        assertThat(surplus).isZero();
        assertThat(waiting).isEqualTo(stock);
        // B2：surplus + waiting + issued == 初值（切换前快照）
        assertThat(surplus + waiting + issued).isEqualTo(stock);
        // B3：明细等式
        assertThat(waiting + issued).isEqualTo(detailSum("physical_marketing_quota_detail", QUOTA_INV_ID));
        assertThat(detailCount("physical_marketing_quota_detail", QUOTA_INV_ID)).isEqualTo(stock);
    }

    @Test
    @DisplayName("B3 探一次再切：surplus=2 时一组 5 笔，前 2 笔成功其余 INSUFFICIENT，明细等式仍成立")
    void detailSumEqualsWaitingPlusIssued_partialSuccess() {
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = 2 WHERE inv_id = ?", QUOTA_INV_ID);

        List<InventoryDeductRequestVO> batch = LongStream.range(0, 5)
                .mapToObj(i -> new InventoryDeductRequestVO(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())))
                .toList();

        List<DeductResult> results =
                inventoryRepository.flushDeductGroup(InventorySegmentVO.QUOTA, QUOTA_INV_ID, batch);

        assertThat(results).as("逐件结果，前 k 笔成功").hasSize(5);
        assertThat(results.subList(0, 2)).containsExactly(DeductResult.DEDUCTED, DeductResult.DEDUCTED);
        assertThat(results.subList(2, 5)).containsExactly(DeductResult.INSUFFICIENT, DeductResult.INSUFFICIENT,
                DeductResult.INSUFFICIENT);

        // 第二事务路径必须让明细与台账仍然平：插进去的是 k 行不是 N 行
        assertThat(detailCount("physical_marketing_quota_detail", QUOTA_INV_ID)).as("只插 k 行").isEqualTo(2);
        assertThat(waitingOf(QUOTA_INV_ID)).isEqualTo(2);
        assertThat(surplusOf("physical_marketing_quota", QUOTA_INV_ID)).isZero();
        // B3：waiting + issued == SUM(quantity)，部分成功时仍相等
        assertThat(waitingOf(QUOTA_INV_ID) + issuedOf("physical_marketing_quota", QUOTA_INV_ID))
                .isEqualTo(detailSum("physical_marketing_quota_detail", QUOTA_INV_ID));
        // B2：三列守恒
        assertThat(surplusOf("physical_marketing_quota", QUOTA_INV_ID) + waitingOf(QUOTA_INV_ID)
                + issuedOf("physical_marketing_quota", QUOTA_INV_ID)).isEqualTo(2L);
    }

    @Test
    @DisplayName("B4 整批撞 uk：扣 0、直接提交、按成功回放，明细仍 1 行、净扣 1 份")
    void wholeBatchDuplicate_replaysAsSuccessWithoutSecondDeduction() {
        String recordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        assertThat(inventoryQuotaService.preDeduct(InventorySegmentVO.VIRTUAL, VIRTUAL_INV_ID, 3,
                InventoryBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        long issuedAfterFirst = issuedOf("virtual_inventory_quota", VIRTUAL_INV_ID);

        // 同 bizId 再走一次落库方法（重放）：整批撞 uk ⇒ 扣 0、提交、按成功回放
        List<InventoryDeductRequestVO> replay = List.of(new InventoryDeductRequestVO(
                InventorySegmentVO.VIRTUAL, VIRTUAL_INV_ID, 3, InventoryBizTypes.LOTTERY_DRAW, recordId));
        List<DeductResult> results =
                inventoryRepository.flushDeductGroup(InventorySegmentVO.VIRTUAL, VIRTUAL_INV_ID, replay);

        assertThat(results).containsExactly(DeductResult.DEDUCTED);
        assertThat(detailCount("virtual_inventory_quota_detail", VIRTUAL_INV_ID)).as("明细仍 1 行").isEqualTo(1);
        assertThat(issuedOf("virtual_inventory_quota", VIRTUAL_INV_ID))
                .as("净扣 1 份").isEqualTo(issuedAfterFirst);
        assertThat(surplusOf("virtual_inventory_quota", VIRTUAL_INV_ID)).isEqualTo(INITIAL_SURPLUS - 3);
    }

    @Test
    @DisplayName("B4 部分撞键：回查命中行并从本次扣减量剔除，其余照扣")
    void partialDuplicate_excludesExistingFromDeduction() {
        String duplicateRecordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        assertThat(inventoryQuotaService.preDeduct(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 2,
                InventoryBizTypes.LOTTERY_DRAW, duplicateRecordId)).isTrue();
        long waitingAfterFirst = waitingOf(QUOTA_INV_ID);

        // 一组三笔：第一笔是重放，后两笔是新扣
        List<InventoryDeductRequestVO> batch = List.of(
                new InventoryDeductRequestVO(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 2,
                        InventoryBizTypes.LOTTERY_DRAW, duplicateRecordId),
                new InventoryDeductRequestVO(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())),
                new InventoryDeductRequestVO(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1,
                        InventoryBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())));

        List<DeductResult> results =
                inventoryRepository.flushDeductGroup(InventorySegmentVO.QUOTA, QUOTA_INV_ID, batch);

        assertThat(results).containsExactly(DeductResult.DEDUCTED, DeductResult.DEDUCTED, DeductResult.DEDUCTED);
        assertThat(detailCount("physical_marketing_quota_detail", QUOTA_INV_ID)).as("新插 2 行 + 原有 1 行").isEqualTo(3);
        assertThat(waitingOf(QUOTA_INV_ID)).as("重放件的量被剔除，只扣新件的 2 份")
                .isEqualTo(waitingAfterFirst + 2);
        assertThat(surplusOf("physical_marketing_quota", QUOTA_INV_ID)).isEqualTo(INITIAL_SURPLUS - 4);
        // B3 在部分撞键下仍成立
        assertThat(waitingOf(QUOTA_INV_ID) + issuedOf("physical_marketing_quota", QUOTA_INV_ID))
                .isEqualTo(detailSum("physical_marketing_quota_detail", QUOTA_INV_ID));
    }

    @Test
    @DisplayName("扣不动 ⇒ 第一事务整体回滚不留明细残痕；恢复余量后凭同一 bizId 重试，唯一键兜底不双扣")
    void transactionFailure_rollsBackAndRetryDoesNotDoubleDeduct() {
        String recordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        // 余量拉成 0：一组一笔也扣不动，第一事务（含明细 INSERT）整体回滚
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = 0 WHERE inv_id = ?", QUOTA_INV_ID);
        List<InventoryDeductRequestVO> batch = List.of(new InventoryDeductRequestVO(
                InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1, InventoryBizTypes.LOTTERY_DRAW, recordId));
        assertThat(inventoryRepository.flushDeductGroup(InventorySegmentVO.QUOTA, QUOTA_INV_ID, batch))
                .as("确定没扣：全组 INSUFFICIENT，不落明细")
                .containsExactly(DeductResult.INSUFFICIENT);
        assertThat(detailCount("physical_marketing_quota_detail", QUOTA_INV_ID))
                .as("第一事务已回滚，明细不留残痕").isZero();

        // 恢复余量后凭同一 bizId 重试：正常扣一份（唯一键兜底，不会双扣）
        jdbcTemplate.update("UPDATE physical_marketing_quota SET surplus_qty = 5 WHERE inv_id = ?", QUOTA_INV_ID);
        assertThat(inventoryQuotaService.preDeduct(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1,
                InventoryBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        assertThat(detailCount("physical_marketing_quota_detail", QUOTA_INV_ID)).isEqualTo(1);
        assertThat(waitingOf(QUOTA_INV_ID)).isEqualTo(1);
    }
}
