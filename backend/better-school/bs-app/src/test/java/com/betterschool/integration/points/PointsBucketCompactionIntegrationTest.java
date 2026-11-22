package com.betterschool.integration.points;

import com.betterschool.integration.support.PointsApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桶合并（points-compaction 票）：同用户的「有效小额桶」（remain &le; 10 且数量 &ge; 2）并成一桶——
 * 新桶 amount = remain = SUM、expire_at = 合并集合最大值（把小桶有效期延长到其中最晚到期，产品口径），
 * 旧桶置零不删行（发放流水留审计）。SUM(remain) 合并前后守恒、余额不变，合并行不进用户流水。
 */
@DisplayName("积分 · 桶合并")
class PointsBucketCompactionIntegrationTest extends PointsApiTestBase {

    private static final String USER_ID = "user-1007";

    @Test
    @DisplayName("3 张小额桶并成一桶：余额守恒、expire_at 取最大值、旧桶归零不删行")
    void compact_mergesSmallBuckets_andConservesBalance() {
        LocalDateTime maxExpire = LocalDateTime.now().plusDays(30);
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(10), "REBATE", "c0001");
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(20), "REBATE", "c0002");
        insertBucket(USER_ID, 5, 5, maxExpire, "REBATE", "c0003");

        int merged = pointsAccountService.compactSmallBuckets();

        assertThat(merged).as("本轮清零（合并）的旧桶数").isEqualTo(3);
        assertThat(bucketCountOf(USER_ID)).as("不删行：1 张新桶 + 3 张归零旧行").isEqualTo(4);

        Map<String, Object> validBucket = jdbcTemplate.queryForMap(
                "SELECT amount, remain, expire_at, reason_type FROM points_bucket "
                        + "WHERE user_id = ? AND remain > 0", USER_ID);
        assertThat(validBucket.get("remain")).as("新桶 remain = SUM(15)").isEqualTo(15);
        assertThat(validBucket.get("amount")).isEqualTo(15);
        assertThat(validBucket.get("reason_type")).isEqualTo("COMPACTION");
        assertThat((LocalDateTime) validBucket.get("expire_at"))
                .as("新桶 expire_at = 合并集合最大值（小桶有效期延长到最晚到期）")
                .isEqualToIgnoringSeconds(maxExpire);

        // 守恒：SUM(remain) 合并前后不变，余额读不受影响
        assertThat(pointsAccountService.getBalance(USER_ID)).as("余额守恒").isEqualTo(15);
    }

    @Test
    @DisplayName("单张小额桶：不合并")
    void compact_singleSmallBucket_isNoop() {
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(10), "REBATE", "c0101");

        assertThat(pointsAccountService.compactSmallBuckets()).isZero();
        assertThat(bucketCountOf(USER_ID)).isEqualTo(1);
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(5);
    }

    @Test
    @DisplayName("大桶（remain > 阈值 10）不参与合并")
    void compact_bigBucket_isNotSelected() {
        insertBucket(USER_ID, 20, 20, LocalDateTime.now().plusDays(10), "LOTTERY_WIN", "c0201");
        insertBucket(USER_ID, 20, 20, LocalDateTime.now().plusDays(11), "LOTTERY_WIN", "c0202");

        assertThat(pointsAccountService.compactSmallBuckets()).isZero();
        assertThat(bucketCountOf(USER_ID)).isEqualTo(2);
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(40);
    }

    @Test
    @DisplayName("过期小桶不参与合并：只合并有效小额桶")
    void compact_ignoresExpiredBuckets() {
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().minusDays(1), "REBATE", "c0301");
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(10), "REBATE", "c0302");
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(20), "REBATE", "c0303");

        assertThat(pointsAccountService.compactSmallBuckets()).as("过期桶不计入候选，只合并 2 张有效桶")
                .isEqualTo(2);

        assertThat(bucketCountOf(USER_ID)).as("过期行不动：1 张过期 + 1 张新桶 + 2 张归零旧行").isEqualTo(4);
        assertThat(pointsAccountService.getBalance(USER_ID)).as("余额守恒（过期桶本来就不计读）").isEqualTo(10);
    }

    @Test
    @DisplayName("合并后 FIFO：新桶 expire_at = max，扣减仍先扣更早过期的桶")
    void compactedBucket_staysLastInFifoOrder() {
        // 大桶最早过期（不参与合并），两张小桶更晚过期、合并后 expire_at 取最大值
        long earlyBigBucketId = insertBucket(USER_ID, 20, 20, LocalDateTime.now().plusDays(2),
                "LOTTERY_WIN", "c0401");
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(10), "REBATE", "c0402");
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(20), "REBATE", "c0403");

        assertThat(pointsAccountService.compactSmallBuckets()).isEqualTo(2);

        // 扣 20：按 FIFO 先扣最早过期的大桶（恰好扣光），合并桶（最晚过期）不动
        var result = pointsAccountService.deduct(USER_ID, 20,
                com.betterschool.types.points.PointsReasonType.REDEEM_PRIZE, "order-c0401");
        assertThat(result.isSuccess()).isTrue();

        assertThat(bucketRemainOf(earlyBigBucketId)).as("FIFO 先扣最早过期的桶").isZero();
        Integer compactedRemain = jdbcTemplate.queryForObject(
                "SELECT remain FROM points_bucket WHERE user_id = ? AND reason_type = 'COMPACTION'",
                Integer.class, USER_ID);
        assertThat(compactedRemain).as("合并桶排在扣减顺序最后，本笔不被碰").isEqualTo(10);
    }

    @Test
    @DisplayName("合并对用户不可见：流水不出现 COMPACTION 行（HTTP 与直查同口径）")
    void compaction_isInvisibleInUserTransactions() {
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(10), "REBATE", "c0501");
        insertBucket(USER_ID, 5, 5, LocalDateTime.now().plusDays(20), "REBATE", "c0502");

        assertThat(pointsAccountService.compactSmallBuckets()).isEqualTo(2);

        // HTTP 流水：只剩两笔原始入账（IN），归零旧行与新合并桶都不出现
        JsonNode page = getJson("/api/points/transactions?page=1&pageSize=50", USER_ID).path("data");
        assertThat(page.path("total").asLong()).as("合并是账务整形，不产生用户可见流水").isEqualTo(2);
        for (JsonNode row : page.path("list")) {
            assertThat(row.path("reasonType").asText()).isNotEqualTo("COMPACTION");
        }

        // 直查投影（与 selectTransactions 同口径）也不出现 COMPACTION
        List<Map<String, Object>> rows = transactionsOf(USER_ID);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.get("reason_type")).isNotEqualTo("COMPACTION"));
    }
}
