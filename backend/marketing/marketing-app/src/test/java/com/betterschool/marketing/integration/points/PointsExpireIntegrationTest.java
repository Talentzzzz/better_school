package com.betterschool.marketing.integration.points;

import com.betterschool.marketing.integration.support.PointsApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期（桶模型验收，读路径懒过期语义）：
 * 余额读 = 未过期桶 SUM(remain)——过期桶被读路径 {@code expire_at > NOW()} 滤掉，过期瞬间读数即扣；
 * 扫描任务只做账务收拾（置 0、不删行、留审计、不发事件），其快慢不影响任何读数。
 */
@DisplayName("积分 · 桶过期")
class PointsExpireIntegrationTest extends PointsApiTestBase {

    private static final String USER_ID = "user-1002";

    @Test
    @DisplayName("懒过期：过期桶不计入余额")
    void expiredBucket_excludedFromBalance_andStaysInTransactions() {
        long expiredBucketId = insertBucket(USER_ID, 50, 50, LocalDateTime.now().minusDays(1), "LOTTERY_WIN", "80001");
        insertBucket(USER_ID, 30, 30, LocalDateTime.now().plusDays(10), "LOTTERY_WIN", "80002");

        // 读路径懒过期：过期桶被 expire_at > NOW() 滤掉，余额只算有效桶的 30
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(30);
        JsonNode balance = getJson("/api/points/balance", USER_ID);
        assertSuccess(balance, "查余额");
        assertThat(balance.path("data").path("balance").asInt()).isEqualTo(30);

        // 流水不过滤过期：两笔入账都看得到（审计要看得见过期这笔）
        JsonNode page = getJson("/api/points/transactions?page=1&pageSize=10", USER_ID).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(2);

        // 落账前读路径不写库
        assertThat(bucketRemainOf(expiredBucketId)).as("读路径不改数据").isEqualTo(50);
    }

    @Test
    @DisplayName("过期落账清理：置 0，不删行、不发事件")
    void expireScan_zeroesRemainWithoutDeletingRowOrEmittingEvent() {
        long expiredBucketId = insertBucket(USER_ID, 50, 50, LocalDateTime.now().minusDays(1), "LOTTERY_WIN", "80011");
        long validBucketId = insertBucket(USER_ID, 30, 30, LocalDateTime.now().plusDays(10), "LOTTERY_WIN", "80012");

        int cleaned = pointsAccountService.expireBuckets();

        assertThat(cleaned).as("只清理过期且仍有余量的一行").isEqualTo(1);
        assertThat(bucketRemainOf(expiredBucketId)).as("置零但行还在（留审计）").isZero();
        assertThat(bucketRemainOf(validBucketId)).as("未过期的桶不动").isEqualTo(30);
        assertThat(bucketCountOf(USER_ID)).as("不删行").isEqualTo(2);
        assertThat(outboxPayloads("bs-points-changed")).as("过期不是积分变动，不发事件").isEmpty();

        // 置 0 前后余额读同值（过期桶本来就被读路径滤掉），清理只是账务收拾
        assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(30);
    }
}
