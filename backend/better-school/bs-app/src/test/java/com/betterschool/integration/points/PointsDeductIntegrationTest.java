package com.betterschool.integration.points;

import com.betterschool.api.points.dto.PointsDeductRequestDTO;
import com.betterschool.api.points.dto.PointsDeductResponseDTO;
import com.betterschool.integration.support.PointsApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兑换扣分（桶模型验收：FIFO 跨桶扣减 + 凑不够不产生半扣）。
 * <p>
 * 走的是 Dubbo Provider Bean（{@code PointsExchangeDubboService}）——它是抽奖服务真正会打到的那个入口，
 * 只是测试进程内直接调用而不是跨网络（真跨进程调用归联调票）。
 */
@DisplayName("积分 · 兑换扣分（FIFO）")
class PointsDeductIntegrationTest extends PointsApiTestBase {

    private static final String USER_ID = "user-1003";
    private static final String REDEEM_ORDER_ID = "90001";
    /** 并发用例独占的兑换单号：与顺序用例的 90001 分开，免得前置态互相解释 */
    private static final String CONCURRENT_REDEEM_ORDER_ID = "99001";

    private PointsDeductResponseDTO deduct(PointsDeductRequestDTO pointsDeductRequestDTO) {
        return pointsExchangeDubboService.deduct(pointsDeductRequestDTO);
    }

    private PointsDeductRequestDTO request(int amount, String reasonType, String refId) {
        PointsDeductRequestDTO pointsDeductRequestDTO = new PointsDeductRequestDTO();
        pointsDeductRequestDTO.setUserId(USER_ID);
        pointsDeductRequestDTO.setAmount(amount);
        pointsDeductRequestDTO.setReasonType(reasonType);
        pointsDeductRequestDTO.setRefId(refId);
        return pointsDeductRequestDTO;
    }

    /** 桶行 id → 桶业务 ID（消费明细的 bucket_id 指向业务 ID，不是行 id；uuid-biz-id 票 07 起 bucket_id 是 UUID 字符串） */
    private String bucketBusinessIdOf(long bucketRowId) {
        return jdbcTemplate.queryForObject("SELECT bucket_id FROM points_bucket WHERE id = ?", String.class, bucketRowId);
    }

    /** 消费明细按桶归集：bucket 业务 ID → 该桶被扣掉的积分（一行明细就是一张券的一次扣减） */
    private Map<String, Integer> detailAmountsByBucketOf(String userId) {
        return jdbcTemplate.queryForList(
                        "SELECT bucket_id, amount FROM points_detail WHERE user_id = ?", userId).stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("bucket_id"),
                        row -> ((Number) row.get("amount")).intValue(),
                        (first, second) -> {
                            // 同一张券被记了两行明细：这本身就是契约被破坏，给个能直接读懂的失败信息
                            throw new AssertionError("同一张券被记了多行消费明细，扣减量=" + first + " 与 " + second);
                        }));
    }

    /**
     * 两个线程在同一把起跑线上（{@link CyclicBarrier}，两方都到场才放行）同时发起同一笔兑换。
     * 返回两侧的响应对象——顺序不重要，谁先谁后不参与断言。
     */
    private List<PointsDeductResponseDTO> deductConcurrently(int amount, String reasonType, String refId)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startLine = new CyclicBarrier(2);
        try {
            List<Future<PointsDeductResponseDTO>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return deduct(request(amount, reasonType, refId));
                }));
            }
            List<PointsDeductResponseDTO> responses = new ArrayList<>();
            for (Future<PointsDeductResponseDTO> future : futures) {
                try {
                    responses.add(future.get(15, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    // 比 innodb_lock_wait_timeout(50s) 短：两侧互等锁时先撞这里，给一句能直接读懂的话
                    throw new AssertionError("并发兑换超过 15s 未返回，两侧多半在互等锁", e);
                } catch (ExecutionException e) {
                    // 只陈述事实：抛的是哪一侧不可知（两侧身份在并发里本就不可区分），
                    // 「本来就该幂等成功」由下面的 success 断言去说，别在这里替它下结论
                    throw new AssertionError("并发兑换的一侧抛了异常：" + e.getCause(), e.getCause());
                }
            }
            return responses;
        } finally {
            // 被打断的线程可能还在事务里：等它退干净，免得和下个用例的 TRUNCATE 抢元数据锁
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("跨桶 FIFO：先扣最早过期的桶，扣完再扣下一个")
    void deduct_spansBucketsInFifoOrder() {
        long firstBucketId = insertBucket(USER_ID, 60, 60, LocalDateTime.now().plusDays(1), "LOTTERY_WIN", "91001");
        long secondBucketId = insertBucket(USER_ID, 100, 100, LocalDateTime.now().plusDays(10), "LOTTERY_WIN", "91002");

        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(100, "REDEEM_DRAW_COUNT", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isTrue();
        assertThat(pointsDeductResponseDTO.getBalance()).as("160 - 100").isEqualTo(60);
        assertThat(bucketRemainOf(firstBucketId)).as("先过期的那桶被扣光").isZero();
        assertThat(bucketRemainOf(secondBucketId)).as("差额落到后过期的桶").isEqualTo(60);

        // 一笔兑换跨两个桶 = 两行消费明细，每行只记该桶被扣掉多少（bucket_id 是桶业务 ID）
        assertThat(detailCountOf(USER_ID)).isEqualTo(2);
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT bucket_id, amount, reason_type, ref_id FROM points_detail WHERE user_id = ? ORDER BY amount",
                USER_ID);
        assertThat(details.get(0).get("amount")).isEqualTo(40);
        assertThat(details.get(1).get("amount")).isEqualTo(60);
        assertThat(details.get(1).get("reason_type")).isEqualTo("REDEEM_DRAW_COUNT");
        assertThat(String.valueOf(details.get(1).get("ref_id"))).isEqualTo(String.valueOf(REDEEM_ORDER_ID));
        // 明细的 bucket_id 都指向真实存在的桶业务 ID（非物理自增 id）
        List<String> detailBucketIds = details.stream().map(row -> (String) row.get("bucket_id")).toList();
        List<String> bucketIds = jdbcTemplate.queryForList(
                "SELECT bucket_id FROM points_bucket WHERE user_id = ?", String.class, USER_ID);
        assertThat(bucketIds).as("明细引用的桶业务 ID 必须在 points_bucket 里").containsAll(detailBucketIds);

        // 变动事件：delta 为负、balanceAfter 是扣后余额
        JsonNode event = onlyPointsChangedEvent();
        assertThat(event.path("delta").asInt()).isEqualTo(-100);
        assertThat(event.path("balanceAfter").asInt()).isEqualTo(60);
        assertThat(event.path("reasonType").asText()).isEqualTo("REDEEM_DRAW_COUNT");
        assertThat(event.path("refId").asText()).isEqualTo(REDEEM_ORDER_ID);

        // 流水：两行 OUT + 两行 IN
        assertThat(transactionsOf(USER_ID)).extracting(row -> row.get("direction"))
                .containsExactlyInAnyOrder("IN", "IN", "OUT", "OUT");
    }

    @Test
    @DisplayName("跨 4 张券：按过期先后逐张扣光，同到期时间按入账先后，最后一张只扣差额")
    void deduct_acrossMoreThanThreeBuckets_booksEveryBucketItTouches() {
        LocalDateTime now = LocalDateTime.now();
        // 第三、四张到期时间完全相同（同一时刻算出来的值），用来钉住 FIFO 的次序兜底：同到期时间按入账先后（id）
        LocalDateTime sameExpiry = now.plusDays(5);
        long earliest = insertBucket(USER_ID, 20, 20, now.plusDays(1), "LOTTERY_WIN", "96001");
        long second = insertBucket(USER_ID, 30, 30, now.plusDays(3), "LOTTERY_WIN", "96002");
        long third = insertBucket(USER_ID, 25, 25, sameExpiry, "LOTTERY_WIN", "96003");
        long thirdTwin = insertBucket(USER_ID, 40, 40, sameExpiry, "LOTTERY_WIN", "96004");

        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(100, "REDEEM_DRAW_COUNT", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isTrue();
        assertThat(pointsDeductResponseDTO.getBalance()).as("115 - 100").isEqualTo(15);

        // 每张券各自的剩余量：前三张扣光，最后一张只扣差额
        assertThat(bucketRemainOf(earliest)).as("最早过期的券先被扣光").isZero();
        assertThat(bucketRemainOf(second)).as("第二张接着扣光").isZero();
        assertThat(bucketRemainOf(third)).as("同到期时间的两张里，先入账的那张先扣").isZero();
        assertThat(bucketRemainOf(thirdTwin)).as("同到期时间的后一张 40 - 差额 25").isEqualTo(15);

        // 明细：被碰到的四张券各一行，金额逐桶记账，合计等于本次扣分
        Map<String, Integer> deductedByBucket = detailAmountsByBucketOf(USER_ID);
        assertThat(deductedByBucket).hasSize(4);
        assertThat(deductedByBucket).containsEntry(bucketBusinessIdOf(earliest), 20)
                .containsEntry(bucketBusinessIdOf(second), 30)
                .containsEntry(bucketBusinessIdOf(third), 25)
                .containsEntry(bucketBusinessIdOf(thirdTwin), 25);
        assertThat(deductedByBucket.values().stream().mapToInt(Integer::intValue).sum())
                .as("逐桶金额合计 = 本次扣分").isEqualTo(100);

        // 明细的来源业务字段：一笔兑换的所有行共享同一原因与兑换单号
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT reason_type, ref_id FROM points_detail WHERE user_id = ?", USER_ID);
        assertThat(details).allSatisfy(row -> {
            assertThat(row.get("reason_type")).isEqualTo("REDEEM_DRAW_COUNT");
            assertThat(String.valueOf(row.get("ref_id"))).isEqualTo(String.valueOf(REDEEM_ORDER_ID));
        });

        // 变动事件：整笔只发一条，delta 是本次扣分的负数，balanceAfter 是扣后余额
        JsonNode event = onlyPointsChangedEvent();
        assertThat(event.path("delta").asInt()).isEqualTo(-100);
        assertThat(event.path("balanceAfter").asInt()).isEqualTo(15);
        assertThat(event.path("reasonType").asText()).isEqualTo("REDEEM_DRAW_COUNT");
        assertThat(event.path("refId").asText()).isEqualTo(REDEEM_ORDER_ID);
    }

    @Test
    @DisplayName("恰好整笔扣完：被扣的券全部归零，不留下部分扣的残余")
    void deduct_exactlyDrainsBuckets_leavesNoPartialResidue() {
        long first = insertBucket(USER_ID, 30, 30, LocalDateTime.now().plusDays(1), "LOTTERY_WIN", "97001");
        long second = insertBucket(USER_ID, 70, 70, LocalDateTime.now().plusDays(3), "LOTTERY_WIN", "97002");
        long untouched = insertBucket(USER_ID, 50, 50, LocalDateTime.now().plusDays(5), "LOTTERY_WIN", "97003");

        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(100, "REDEEM_DRAW_COUNT", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isTrue();
        assertThat(pointsDeductResponseDTO.getBalance()).as("150 - 100，只剩没被碰到的那张").isEqualTo(50);

        assertThat(bucketRemainOf(first)).as("恰好扣完的第一张归零").isZero();
        assertThat(bucketRemainOf(second)).as("恰好扣完的第二张归零").isZero();
        assertThat(bucketRemainOf(untouched)).as("额度已够，第三张一分不动").isEqualTo(50);

        // 两行明细，各自等额扣光一张券；没有第三行，说明不产生零碎的残余记账
        Map<String, Integer> deductedByBucket = detailAmountsByBucketOf(USER_ID);
        assertThat(deductedByBucket).hasSize(2);
        assertThat(deductedByBucket).containsEntry(bucketBusinessIdOf(first), 30)
                .containsEntry(bucketBusinessIdOf(second), 70)
                .doesNotContainKey(bucketBusinessIdOf(untouched));

        JsonNode event = onlyPointsChangedEvent();
        assertThat(event.path("delta").asInt()).isEqualTo(-100);
        assertThat(event.path("balanceAfter").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("跨 1 张券：退化为单桶扣减，余量与明细口径与单桶一致")
    void deduct_fromSingleBucket_behavesLikeSingleBucketDeduction() {
        long bucketId = insertBucket(USER_ID, 100, 100, LocalDateTime.now().plusDays(5), "LOTTERY_WIN", "98001");

        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(60, "REDEEM_DRAW_COUNT", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isTrue();
        assertThat(pointsDeductResponseDTO.getBalance()).as("100 - 60").isEqualTo(40);
        assertThat(bucketRemainOf(bucketId)).isEqualTo(40);

        Map<String, Integer> deductedByBucket = detailAmountsByBucketOf(USER_ID);
        assertThat(deductedByBucket).containsExactly(Map.entry(bucketBusinessIdOf(bucketId), 60));

        JsonNode event = onlyPointsChangedEvent();
        assertThat(event.path("delta").asInt()).isEqualTo(-60);
        assertThat(event.path("balanceAfter").asInt()).isEqualTo(40);
    }

    @Test
    @DisplayName("并发重复兑换：同一兑换单号两线程同一起跑线发起，只落一份明细、只扣一次、只发一条事件")
    void deduct_sameRedeemOrderFromTwoThreads_chargesOnce() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long earliest = insertBucket(USER_ID, 60, 60, now.plusDays(1), "LOTTERY_WIN", "99011");
        long later = insertBucket(USER_ID, 300, 300, now.plusDays(10), "LOTTERY_WIN", "99012");

        List<PointsDeductResponseDTO> responses =
                deductConcurrently(100, "REDEEM_DRAW_COUNT", CONCURRENT_REDEEM_ORDER_ID);

        // 两侧都不把「并发重复」当系统故障：重复者按幂等成功回报，余额是事务外读到的真实余额
        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.isSuccess())
                    .as("并发重复的一侧应幂等成功，实际 code=%s info=%s", response.getCode(), response.getInfo())
                    .isTrue();
            assertThat(response.getBalance()).as("两侧回报的余额都应是扣后真实余额").isEqualTo(260);
        });

        // 落库侧只扣一次：最早过期的券被扣光，差额落在后一张券上（360 - 100 = 260）
        assertThat(bucketRemainOf(earliest)).as("最早过期的券只被扣一次，恰好归零").isZero();
        assertThat(bucketRemainOf(later)).as("后一张券只承担一次差额").isEqualTo(260);

        // 明细只有一份（被碰到的两张券各一行，合计 100）。
        // 同一张券同一单号插第二行会被 uk 挡住，所以「多扣」不会以多一行明细的样子出现——
        // 它要么表现为余量被多减一次、要么表现为事件多发一条，下面这两处断言兜的就是这个
        Map<String, Integer> deductedByBucket = detailAmountsByBucketOf(USER_ID);
        assertThat(deductedByBucket).hasSize(2);
        assertThat(deductedByBucket).containsEntry(bucketBusinessIdOf(earliest), 60)
                .containsEntry(bucketBusinessIdOf(later), 40);
        assertThat(deductedByBucket.values().stream().mapToInt(Integer::intValue).sum())
                .as("明细金额合计 = 一笔兑换的 100 分").isEqualTo(100);

        // 变动事件整笔只有一条，载荷是这一笔兑换
        JsonNode event = onlyPointsChangedEvent();
        assertThat(event.path("delta").asInt()).isEqualTo(-100);
        assertThat(event.path("balanceAfter").asInt()).isEqualTo(260);
    }

    @Test
    @DisplayName("积分不足：一行都不动（无消费明细、余量不变、无事件）")
    void deduct_insufficientPoints_changesNothing() {
        long bucketId = insertBucket(USER_ID, 50, 50, LocalDateTime.now().plusDays(5), "LOTTERY_WIN", "92001");

        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(100, "REDEEM_PRIZE", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isFalse();
        assertThat(pointsDeductResponseDTO.getCode()).isEqualTo("4101");
        assertThat(pointsDeductResponseDTO.getInfo()).isEqualTo("积分不足");
        assertThat(pointsDeductResponseDTO.getBalance()).as("失败也回报当前余额").isEqualTo(50);
        assertThat(detailCountOf(USER_ID)).isZero();
        assertThat(bucketRemainOf(bucketId)).isEqualTo(50);
        assertThat(outboxPayloads("bs-points-changed")).isEmpty();
    }

    @Test
    @DisplayName("同一兑换单重试：不重复扣分、不重复发事件")
    void deduct_sameRedeemOrderId_isIdempotent() {
        long bucketId = insertBucket(USER_ID, 100, 100, LocalDateTime.now().plusDays(5), "LOTTERY_WIN", "93001");

        deduct(request(30, "REDEEM_DRAW_COUNT", REDEEM_ORDER_ID));
        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(30, "REDEEM_DRAW_COUNT", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isTrue();
        assertThat(pointsDeductResponseDTO.getBalance()).as("只扣一次").isEqualTo(70);
        assertThat(bucketRemainOf(bucketId)).isEqualTo(70);
        assertThat(detailCountOf(USER_ID)).isEqualTo(1);
        assertThat(outboxPayloads("bs-points-changed")).hasSize(1);
    }

    @Test
    @DisplayName("入账原因不是兑换类：参数错误（不当作扣分处理）")
    void deduct_withNonRedeemReason_isRejected() {
        insertBucket(USER_ID, 100, 100, LocalDateTime.now().plusDays(5), "LOTTERY_WIN", "94001");

        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(10, "LOTTERY_WIN", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isFalse();
        assertThat(pointsDeductResponseDTO.getCode()).isEqualTo("400");
        assertThat(detailCountOf(USER_ID)).isZero();
        assertThat(outboxPayloads("bs-points-changed")).isEmpty();
    }

    @Test
    @DisplayName("跳过过期桶：只从有效桶里扣")
    void deduct_ignoresExpiredBuckets() {
        long expiredBucketId = insertBucket(USER_ID, 100, 100, LocalDateTime.now().minusDays(1), "LOTTERY_WIN", "95001");
        insertBucket(USER_ID, 40, 40, LocalDateTime.now().plusDays(3), "LOTTERY_WIN", "95002");

        PointsDeductResponseDTO pointsDeductResponseDTO = deduct(request(40, "REDEEM_DRAW_COUNT", REDEEM_ORDER_ID));

        assertThat(pointsDeductResponseDTO.isSuccess()).isTrue();
        assertThat(pointsDeductResponseDTO.getBalance()).isZero();
        assertThat(bucketRemainOf(expiredBucketId)).as("过期桶不被扣").isEqualTo(100);
    }
}
