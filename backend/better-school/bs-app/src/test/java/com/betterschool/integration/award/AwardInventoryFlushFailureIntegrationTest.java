package com.betterschool.integration.award;

import com.betterschool.domain.award.service.IAwardInventoryService;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.infrastructure.dao.award.IAwardInventoryDetailDao;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.award.AwardInventoryTypeEnum;
import com.betterschool.types.award.AwardBizTypes;
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
class AwardInventoryFlushFailureIntegrationTest extends TestingEnvironment {

    private static final String PHYSICAL_AWARD_ID = "990201";

    @BeforeAll
    static void prepareEnvironment() {
        TestingEnvironment.ensureEnvironment();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IAwardInventoryService awardInventoryService;
    /** 落库失败从 DAO 层注入：批量明细 INSERT 抛异常 = 第一事务整体回滚 */
    @SpyBean
    private IAwardInventoryDetailDao awardInventoryDetailDao;
    @MockBean
    private XxlJobSpringExecutor xxlJobSpringExecutor;

    @BeforeEach
    void resetFixtures() {
        Mockito.reset(awardInventoryDetailDao);
        List.of("award_inventory", "award_inventory_detail")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        // 运营直接投放：实物账户 surplus 100
        jdbcTemplate.update("""
                        INSERT INTO award_inventory (award_id, type, surplus_qty, waiting_qty, issued_qty,
                                                     value_qty, state, create_time, update_time)
                        VALUES (?, 'PHYSICAL', 100, 0, 0, NULL, 0, NOW(), NOW())
                        """, PHYSICAL_AWARD_ID);
    }

    private long detailCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM award_inventory_detail WHERE award_id = ? AND flow_type = 1",
                Long.class, PHYSICAL_AWARD_ID);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct：落库抛 → 回滚 → ERROR；重试净扣一份")
    void flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct() {
        String recordId = "990001";
        doThrow(new IllegalStateException("探针：落库失败（模拟事务回滚）"))
                .when(awardInventoryDetailDao).batchInsertIgnoreDeductDetails(eq(PHYSICAL_AWARD_ID), anyList());

        assertThatThrownBy(() -> awardInventoryService.preDeduct(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                AwardBizTypes.LOTTERY_DRAW, recordId))
                .as("落库失败按可重试出口抛出（调用方 ERROR），不是 false（不切兜底）")
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ResponseCodeEnum.SYSTEM_ERROR.getCode());

        assertThat(detailCount()).as("第一事务已回滚，明细不留残痕").isZero();
        Long waiting = jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM award_inventory WHERE award_id = ?",
                Long.class, PHYSICAL_AWARD_ID);
        assertThat(waiting).as("台账未动").isZero();

        // 恢复：凭同一 bizId 重试（recordId 由「用户 + token」确定性映射），唯一键兜底不双扣
        Mockito.reset(awardInventoryDetailDao);
        assertThat(awardInventoryService.preDeduct(AwardInventoryTypeEnum.PHYSICAL, PHYSICAL_AWARD_ID, 1,
                AwardBizTypes.LOTTERY_DRAW, recordId)).isTrue();
        assertThat(detailCount()).as("重试恰好落一行明细").isEqualTo(1);
        waiting = jdbcTemplate.queryForObject(
                "SELECT waiting_qty FROM award_inventory WHERE award_id = ?",
                Long.class, PHYSICAL_AWARD_ID);
        assertThat(waiting).as("净扣一份").isEqualTo(1L);
    }
}
