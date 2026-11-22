package com.betterschool.integration.points;

import com.betterschool.integration.support.PointsApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期（桶模型验收，读路径懒过期语义）：
 * 余额读 = 未过期桶 SUM(remain)——过期桶被读路径 {@code expire_at > NOW()} 滤掉，过期瞬间读数即扣。
 * 没有过期清理任务：过期判定与过期资产的失效全在读路径，过期行不删、不置零、不发事件。
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

        // 读路径不写库：过期桶的余量原样留着
        assertThat(bucketRemainOf(expiredBucketId)).as("读路径不改数据").isEqualTo(50);
    }
}
