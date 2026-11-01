package com.betterschool.marketing.integration.points;

import com.betterschool.marketing.integration.support.PointsApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立过期（桶模型验收）：读路径一律带 {@code expire_at > NOW()}（懒过期为准），
 * xxl-job 只做 {@code remain = 0} 的落账清理——不删行、留审计、不发事件。
 */
@DisplayName("积分 · 桶过期")
class PointsExpireIntegrationTest extends PointsApiTestBase {

    private static final String USER_ID = "user-1002";

    @Test
    @DisplayName("懒过期：过期桶不计入余额，但仍留在流水里可查")
    void expiredBucket_isExcludedFromBalance_butStaysInTransactions() {
        long expiredBucketId = insertBucket(USER_ID, 50, 50, LocalDateTime.now().minusDays(1), "LOTTERY_WIN", 80001L);
        insertBucket(USER_ID, 30, 30, LocalDateTime.now().plusDays(10), "LOTTERY_WIN", 80002L);

        // 读路径：余额只算未过期的那 30
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(30);
        JsonNode balance = getJson("/api/points/balance", USER_ID);
        assertSuccess(balance, "查余额");
        assertThat(balance.path("data").path("balance").asInt()).isEqualTo(30);

        // 流水不过滤过期：两笔入账都看得到（审计要看得见过期这笔）
        JsonNode page = getJson("/api/points/transactions?page=1&pageSize=10", USER_ID).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(2);

        // 懒过期只影响读数，不写库
        assertThat(bucketRemainOf(expiredBucketId)).as("读路径不改数据").isEqualTo(50);
    }

    @Test
    @DisplayName("过期落账清理：只把 remain 置 0 且不删行、不发事件")
    void expireScan_zeroesRemainWithoutDeletingRowOrEmittingEvent() {
        long expiredBucketId = insertBucket(USER_ID, 50, 50, LocalDateTime.now().minusDays(1), "LOTTERY_WIN", 80011L);
        long validBucketId = insertBucket(USER_ID, 30, 30, LocalDateTime.now().plusDays(10), "LOTTERY_WIN", 80012L);

        int cleaned = pointsAccountService.expireBuckets();

        assertThat(cleaned).as("只清理过期且仍有余量的一行").isEqualTo(1);
        assertThat(bucketRemainOf(expiredBucketId)).as("置零但行还在（留审计）").isZero();
        assertThat(bucketRemainOf(validBucketId)).as("未过期的桶不动").isEqualTo(30);
        assertThat(bucketCountOf(USER_ID)).as("不删行").isEqualTo(2);
        assertThat(outboxPayloads("bs-points-changed")).as("过期不是积分变动，不发事件").isEmpty();
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(30);
    }
}
