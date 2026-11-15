package com.betterschool.marketing.integration.inventory;

import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryRepository;
import com.betterschool.marketing.integration.support.TestingEnvironment;
import com.betterschool.marketing.infrastructure.inventory.dao.IAwardInventoryDetailDao;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * 刷盘失败的可重试出口（acceptance §4.2：{@code mergeResume_*} 的改写对象
 * {@code flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct}）：奖品库存落库方法中途抛异常
 * ⇒ 第一事务整体回滚（明细不留残痕）⇒ 合并器补 {@code FAILED} ⇒ 服务层抛可重试（调用方 ERROR）；
 * 恢复后凭同一 {@code bizId} 重试，唯一键兜底，净扣一份不双扣。
 * <p>
 * {@code @SpyBean} 会另起一个 Spring 上下文；xxl 执行器 handler 注册表是进程级静态 map，
 * mock 掉避免与共享上下文撞名。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("奖品库存落库失败 · FAILED 可重试且不双扣")
class InventoryFlushFailureIntegrationTest extends TestingEnvironment {

    private static final String PHYSICAL_POOL_INV_ID = "9902";
    private static final String PHYSICAL_AWARD_ID = "990201";

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
    /** 落库失败从 DAO 层注入：批量明细 INSERT 抛异常 = 第一事务整体回滚 */
    @SpyBean
    private IAwardInventoryDetailDao awardInventoryDetailDao;
    @MockBean
    private XxlJobSpringExecutor xxlJobSpringExecutor;

    @BeforeEach
    void resetFixtures() {
        Mockito.reset(awardInventoryDetailDao);
        List.of("virtual_inventory_quota", "physical_marketing_quota",
                "draw_strategy_award_inventory", "draw_strategy_award_inventory_detail",
                "inventory_allocation_flow")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        jdbcTemplate.update("""
                        INSERT INTO physical_marketing_quota (inv_id, name, decr, surplus_qty, waiting_qty, issued_qty,
                                                       state, create_time, update_time)
                        VALUES (?, '刷盘失败用例配额池', NULL, 100, 0, 0, 'open', NOW(), NOW())
                        """, PHYSICAL_POOL_INV_ID);
        // 先分配建户：抽奖只扣奖品库存账户（award-inventory 两层模型）
        assertThat(inventoryRepository.allocate(InventorySegmentVO.QUOTA, PHYSICAL_POOL_INV_ID,
                PHYSICAL_AWARD_ID, 100, "ALLOC-" + UUID.randomUUID())).isTrue();
    }

    private long detailCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_strategy_award_inventory_detail WHERE award_id = ? AND flow_type = 1",
                Long.class, PHYSICAL_AWARD_ID);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct：落库抛 → 回滚 → ERROR；重试净扣一份")
    void flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct() {
        String recordId = "990001";
        doThrow(new IllegalStateException("探针：落库失败（模拟事务回滚）"))
                .when(awardInventoryDetailDao).batchInsertIgnoreDeductDetails(eq(PHYSICAL_AWARD_ID), anyList());

        assertThatThrownBy(() -> inventoryQuotaService.preDeduct(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                InventoryBizTypes.LOTTERY_DRAW, recordId))
                .as("落库失败按可重试出口抛出（调用方 ERROR），不是 false（不切兜底）")
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ResponseCode.SYSTEM_ERROR.getCode());

        assertThat(detailCount()).as("第一事务已回滚，明细不留残痕").isZero();
        Long waiting = jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM draw_strategy_award_inventory WHERE award_id = ?",
                Long.class, PHYSICAL_AWARD_ID);
        assertThat(waiting).as("台账未动").isZero();

        // 恢复：凭同一 bizId 重试（recordId 由「用户 + token」确定性映射），唯一键兜底不双扣
        Mockito.reset(awardInventoryDetailDao);
        assertThat(inventoryQuotaService.preDeduct(AwardInventoryType.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                InventoryBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        assertThat(detailCount()).as("重试恰好落一行明细").isEqualTo(1);
        waiting = jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM draw_strategy_award_inventory WHERE award_id = ?",
                Long.class, PHYSICAL_AWARD_ID);
        assertThat(waiting).as("净扣一份").isEqualTo(1L);
    }
}
