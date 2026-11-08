package com.betterschool.marketing.integration.inventory;

import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.infrastructure.inventory.dao.IPhysicalMarketingQuotaDetailDao;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * 刷盘失败的可重试出口（acceptance §4.2：{@code mergeResume_*} 的改写对象
 * {@code flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct}）：落库方法中途抛异常
 * ⇒ 第一事务整体回滚（明细不留残痕）⇒ 合并器补 {@code FAILED} ⇒ 服务层抛可重试（调用方 ERROR）；
 * 恢复后凭同一 {@code bizId} 重试，唯一键兜底，净扣一份不双扣。
 * <p>
 * {@code @SpyBean} 会另起一个 Spring 上下文；xxl 执行器 handler 注册表是进程级静态 map，
 * mock 掉避免与共享上下文撞名。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("库存落库失败 · FAILED 可重试且不双扣")
class InventoryFlushFailureIntegrationTest extends TestingEnvironment {

    private static final String QUOTA_INV_ID = "9901";

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IInventoryQuotaService inventoryQuotaService;
    /** 落库失败从 DAO 层注入：批量明细 INSERT 抛异常 = 第一事务整体回滚 */
    @SpyBean
    private IPhysicalMarketingQuotaDetailDao physicalMarketingQuotaDetailDao;
    @MockBean
    private XxlJobSpringExecutor xxlJobSpringExecutor;

    @BeforeEach
    void resetFixtures() {
        Mockito.reset(physicalMarketingQuotaDetailDao);
        List.of("physical_marketing_quota", "physical_marketing_quota_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        jdbcTemplate.update("""
                        INSERT INTO physical_marketing_quota (inv_id, name, decr, surplus_qty, waiting_qty, issued_qty,
                                                              state, create_time, update_time)
                        VALUES (?, '刷盘失败用例配额', NULL, 100, 0, 0, 'open', NOW(), NOW())
                        """, QUOTA_INV_ID);
    }

    private long detailCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM physical_marketing_quota_detail WHERE inv_id = ?", Long.class, QUOTA_INV_ID);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct：落库抛 → 回滚 → ERROR；重试净扣一份")
    void flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct() {
        String recordId = "990001";
        doThrow(new IllegalStateException("探针：落库失败（模拟事务回滚）"))
                .when(physicalMarketingQuotaDetailDao).batchInsertIgnoreDeductDetails(eq(QUOTA_INV_ID), anyList());

        assertThatThrownBy(() -> inventoryQuotaService.preDeduct(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1,
                InventoryBizTypes.LOTTERY_DRAW, recordId))
                .as("落库失败按可重试出口抛出（调用方 ERROR），不是 false（不切兜底）")
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());

        assertThat(detailCount()).as("第一事务已回滚，明细不留残痕").isZero();
        Long waiting = jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM physical_marketing_quota WHERE inv_id = ?", Long.class, QUOTA_INV_ID);
        assertThat(waiting).as("台账未动").isZero();

        // 恢复：凭同一 bizId 重试（recordId 由「用户 + token」确定性映射），唯一键兜底不双扣
        Mockito.reset(physicalMarketingQuotaDetailDao);
        assertThat(inventoryQuotaService.preDeduct(InventorySegmentVO.QUOTA, QUOTA_INV_ID, 1,
                InventoryBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        assertThat(detailCount()).as("重试恰好落一行明细").isEqualTo(1);
        waiting = jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM physical_marketing_quota WHERE inv_id = ?", Long.class, QUOTA_INV_ID);
        assertThat(waiting).as("净扣一份").isEqualTo(1L);
    }
}
