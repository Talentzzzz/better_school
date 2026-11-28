package com.betterschool.integration.award;

import com.betterschool.domain.award.model.valobj.DeductResultEnum;
import com.betterschool.domain.award.model.valobj.AwardDeductRequestVO;
import com.betterschool.domain.award.service.IAwardInventoryService;
import com.betterschool.infrastructure.adapter.repository.award.AwardInventoryFlushService;
import com.betterschool.infrastructure.adapter.repository.award.AwardInventoryMergeWriter;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
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
 * 奖品库存守恒判据（acceptance.md 判据名照抄，口径按 §0 新守恒式）：
 * <ul>
 *   <li><b>B1 零超卖</b>：奖品库存账户 {@code surplus = k} 时并发 N 笔恰好 k 笔成功，终值 0，
 *       不为负由台账 {@code UPDATE} 的余量守卫在数据库层面保证，这里锁终值与成功笔数；</li>
 *   <li><b>B2 三列守恒</b>：逐账户 {@code surplus + waiting + issued == 投放量}；</li>
 *   <li><b>B3 明细等式</b>：{@code waiting + issued == SUM(quantity) WHERE flow_type = 1}，
 *       含「探一次再切」分支（{@code k <} 批内笔数时仍相等）；</li>
 *   <li><b>B4 预扣幂等</b>：同 {@code recordId} / {@code bizId} 重放，明细仍 1 行、净扣 1 份；
 *       整批撞 {@code uk_award_biz} ⇒ 扣 0、直接提交、按成功回放；部分撞键 ⇒ 命中行从本次扣减量剔除。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("奖品库存守恒 · B0–B4")
class AwardInventoryConservationIntegrationTest extends TestingEnvironment {

    private static final String VIRTUAL_AWARD_ID = "980101";
    private static final String PHYSICAL_AWARD_ID = "980201";
    private static final long INITIAL_SURPLUS = 100L;
    private static final AtomicLong RECORD_SEQ = new AtomicLong(98_000_000L);

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IAwardInventoryService awardInventoryService;
    @Autowired
    private AwardInventoryMergeWriter mergeWriter;
    /** 落库真身：B3/B4 断的是「一组一个事务」的 SQL 语义，直接打落库层（合并只是它的调用者之一） */
    @Autowired
    private AwardInventoryFlushService awardInventoryFlushService;

    @BeforeEach
    void resetFixtures() {
        List.of("award_inventory", "award_inventory_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        insertAccount(VIRTUAL_AWARD_ID, "VIRTUAL", INITIAL_SURPLUS);
        insertAccount(PHYSICAL_AWARD_ID, "PHYSICAL", INITIAL_SURPLUS);
    }

    /** 运营直接投放账户 */
    private void insertAccount(String awardId, String type, long surplusQty) {
        jdbcTemplate.update("""
                        INSERT INTO award_inventory (award_id, type, surplus_qty, waiting_qty, issued_qty,
                                                     value_qty, state, create_time, update_time)
                        VALUES (?, ?, ?, 0, 0, ?, 0, NOW(), NOW())
                        """, awardId, type, surplusQty, "VIRTUAL".equals(type) ? 10 : null);
    }

    private long surplusOf(String awardId) {
        return jdbcTemplate.queryForObject(
                "SELECT surplus_qty FROM award_inventory WHERE award_id = ?", Long.class, awardId);
    }

    private long issuedOf(String awardId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_qty FROM award_inventory WHERE award_id = ?", Long.class, awardId);
    }

    private long waitingOf(String awardId) {
        return jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM award_inventory WHERE award_id = ?", Long.class, awardId);
    }

    private long detailCount(String awardId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM award_inventory_detail WHERE award_id = ?", Long.class, awardId);
        return count == null ? 0 : count;
    }

    private long detailSum(String awardId, int flowType) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM award_inventory_detail"
                        + " WHERE award_id = ? AND flow_type = ?",
                Long.class, awardId, flowType);
        return sum == null ? 0 : sum;
    }

    @Test
    @DisplayName("B1 零超卖：账户 surplus = 20 时并发 50 笔恰好 20 笔成功，终值 0，明细 20 行")
    void noOversellUnderConcurrency() throws Exception {
        jdbcTemplate.update("UPDATE award_inventory SET surplus_qty = 20 WHERE award_id = ?",
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
            // B2：守恒到 0（初始投放量 20 = surplus + issued）
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
                return awardInventoryService.preDeduct(AwardInventoryTypeEnum.VIRTUAL, VIRTUAL_AWARD_ID, 1,
                        AwardBizTypes.LOTTERY_DRAW, recordId);
            } catch (AppException retryable) {
                assertThat(retryable.getCode())
                        .as("只有 SYSTEM_ERROR（999）是可重试出口，其余异常原样上抛")
                        .isEqualTo(ResponseCodeEnum.SYSTEM_ERROR.getCode());
                // RETRY_LATER：还在处理中或已回滚，等一下再重放
                Thread.sleep(20);
            }
        }
        throw new AssertionError("预扣重放 100 次仍未落定 recordId=" + recordId);
    }

    @Test
    @DisplayName("B2/B3 三列守恒与明细等式：一半成功一半不足，surplus + waiting + issued == 初始投放量")
    void ledgerSumEqualsAllocatedSnapshot() {
        int stock = 10;
        jdbcTemplate.update("UPDATE award_inventory SET surplus_qty = ? WHERE award_id = ?",
                stock, PHYSICAL_AWARD_ID);

        for (int i = 0; i < 20; i++) {
            awardInventoryService.preDeduct(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                    AwardBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet()));
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
        // B2：surplus + waiting + issued == 初始投放量（切换前快照）
        assertThat(surplus + waiting + issued).isEqualTo(stock);
        // B3：明细等式
        assertThat(waiting + issued).isEqualTo(detailSum(PHYSICAL_AWARD_ID, 1));
        assertThat(detailCount(PHYSICAL_AWARD_ID)).as("预扣 10 行").isEqualTo(stock);
    }

    @Test
    @DisplayName("B3 探一次再切：账户 surplus=2 时一组 5 笔，前 2 笔成功其余 INSUFFICIENT，明细等式仍成立")
    void detailSumEqualsWaitingPlusIssued_partialSuccess() {
        jdbcTemplate.update("UPDATE award_inventory SET surplus_qty = 2 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);

        List<AwardDeductRequestVO> batch = LongStream.range(0, 5)
                .mapToObj(i -> new AwardDeductRequestVO(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                        AwardBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())))
                .toList();

        List<DeductResultEnum> results =
                awardInventoryFlushService.flushDeductGroup(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, batch);

        assertThat(results).as("逐件结果，前 k 笔成功").hasSize(5);
        assertThat(results.subList(0, 2)).containsExactly(DeductResultEnum.DEDUCTED, DeductResultEnum.DEDUCTED);
        assertThat(results.subList(2, 5)).containsExactly(DeductResultEnum.INSUFFICIENT, DeductResultEnum.INSUFFICIENT,
                DeductResultEnum.INSUFFICIENT);

        // 第二事务路径必须让明细与台账仍然平：插进去的是 k 行不是 N 行
        assertThat(detailCount(PHYSICAL_AWARD_ID)).as("只有预扣 k=2 行").isEqualTo(2);
        assertThat(waitingOf(PHYSICAL_AWARD_ID)).isEqualTo(2);
        assertThat(surplusOf(PHYSICAL_AWARD_ID)).isZero();
        // B3：waiting + issued == SUM(flow_type = 1)，部分成功时仍相等
        assertThat(waitingOf(PHYSICAL_AWARD_ID) + issuedOf(PHYSICAL_AWARD_ID))
                .isEqualTo(detailSum(PHYSICAL_AWARD_ID, 1));
        // B2：三列守恒（对初始投放量 2）
        assertThat(surplusOf(PHYSICAL_AWARD_ID) + waitingOf(PHYSICAL_AWARD_ID)
                + issuedOf(PHYSICAL_AWARD_ID)).isEqualTo(2L);
    }

    @Test
    @DisplayName("B4 整批撞 uk：扣 0、直接提交、按成功回放，明细仍 1 行、净扣 1 份")
    void wholeBatchDuplicate_replaysAsSuccessWithoutSecondDeduction() {
        String recordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        assertThat(awardInventoryService.preDeduct(AwardInventoryTypeEnum.VIRTUAL, VIRTUAL_AWARD_ID, 3,
                AwardBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        long issuedAfterFirst = issuedOf(VIRTUAL_AWARD_ID);

        // 同 bizId 再走一次落库方法（重放）：整批撞 uk ⇒ 扣 0、提交、按成功回放
        List<AwardDeductRequestVO> replay = List.of(new AwardDeductRequestVO(
                AwardInventoryTypeEnum.VIRTUAL, VIRTUAL_AWARD_ID, 3, AwardBizTypes.LOTTERY_DRAW, recordId));
        List<DeductResultEnum> results =
                awardInventoryFlushService.flushDeductGroup(AwardInventoryTypeEnum.VIRTUAL, VIRTUAL_AWARD_ID, replay);

        assertThat(results).containsExactly(DeductResultEnum.DEDUCTED);
        assertThat(detailCount(VIRTUAL_AWARD_ID)).as("预扣仍 1 行").isEqualTo(1);
        assertThat(issuedOf(VIRTUAL_AWARD_ID))
                .as("净扣 1 份").isEqualTo(issuedAfterFirst);
        assertThat(surplusOf(VIRTUAL_AWARD_ID)).isEqualTo(INITIAL_SURPLUS - 3);
    }

    @Test
    @DisplayName("B4 部分撞键：回查命中行并从本次扣减量剔除，其余照扣；主档与兜底两账户互不撞键")
    void partialDuplicate_excludesExistingFromDeduction() {
        String duplicateRecordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        assertThat(awardInventoryService.preDeduct(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 2,
                AwardBizTypes.LOTTERY_DRAW, duplicateRecordId)).isTrue();
        long waitingAfterFirst = waitingOf(PHYSICAL_AWARD_ID);

        // 一组三笔：第一笔是重放，后两笔是新扣
        List<AwardDeductRequestVO> batch = List.of(
                new AwardDeductRequestVO(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 2,
                        AwardBizTypes.LOTTERY_DRAW, duplicateRecordId),
                new AwardDeductRequestVO(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                        AwardBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())),
                new AwardDeductRequestVO(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                        AwardBizTypes.LOTTERY_DRAW, String.valueOf(RECORD_SEQ.incrementAndGet())));

        List<DeductResultEnum> results =
                awardInventoryFlushService.flushDeductGroup(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, batch);

        assertThat(results).containsExactly(DeductResultEnum.DEDUCTED, DeductResultEnum.DEDUCTED, DeductResultEnum.DEDUCTED);
        assertThat(detailCount(PHYSICAL_AWARD_ID)).as("预扣 3 行").isEqualTo(3);
        assertThat(waitingOf(PHYSICAL_AWARD_ID)).as("重放件的量被剔除，只扣新件的 2 份")
                .isEqualTo(waitingAfterFirst + 2);
        assertThat(surplusOf(PHYSICAL_AWARD_ID)).isEqualTo(INITIAL_SURPLUS - 4);
        // B3 在部分撞键下仍成立
        assertThat(waitingOf(PHYSICAL_AWARD_ID) + issuedOf(PHYSICAL_AWARD_ID))
                .isEqualTo(detailSum(PHYSICAL_AWARD_ID, 1));
    }

    @Test
    @DisplayName("扣不动 ⇒ 第一事务整体回滚不留明细残痕；恢复余量后凭同一 bizId 重试，唯一键兜底不双扣")
    void transactionFailure_rollsBackAndRetryDoesNotDoubleDeduct() {
        String recordId = String.valueOf(RECORD_SEQ.incrementAndGet());
        // 余量拉成 0：一组一笔也扣不动，第一事务（含明细 INSERT）整体回滚
        jdbcTemplate.update("UPDATE award_inventory SET surplus_qty = 0 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);
        List<AwardDeductRequestVO> batch = List.of(new AwardDeductRequestVO(
                AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1, AwardBizTypes.LOTTERY_DRAW, recordId));
        assertThat(awardInventoryFlushService.flushDeductGroup(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, batch))
                .as("确定没扣：全组 INSUFFICIENT，不落明细")
                .containsExactly(DeductResultEnum.INSUFFICIENT);
        assertThat(detailSum(PHYSICAL_AWARD_ID, 1))
                .as("第一事务已回滚，预扣明细不留残痕").isZero();

        // 恢复余量后凭同一 bizId 重试：正常扣一份（唯一键兜底，不会双扣）
        jdbcTemplate.update("UPDATE award_inventory SET surplus_qty = 5 WHERE award_id = ?",
                PHYSICAL_AWARD_ID);
        assertThat(awardInventoryService.preDeduct(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                AwardBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        assertThat(detailSum(PHYSICAL_AWARD_ID, 1)).isEqualTo(1L);
        assertThat(waitingOf(PHYSICAL_AWARD_ID)).isEqualTo(1L);
    }
}
