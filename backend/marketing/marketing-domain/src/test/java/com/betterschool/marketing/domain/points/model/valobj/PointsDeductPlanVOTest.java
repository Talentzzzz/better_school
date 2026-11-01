package com.betterschool.marketing.domain.points.model.valobj;

import com.betterschool.marketing.domain.points.model.entity.PointsBucketEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 扣减方案的计算是纯内存步骤：喂进去「按 FIFO 排好的可用桶」与本次额度，吐出两个集合。
 * 这些用例不连库、不起 Spring——方案的正确性与并发、SQL、事务形状无关。
 */
@DisplayName("积分 · 扣减方案（纯内存计算）")
class PointsDeductPlanVOTest {

    /** 方案只关心「哪个桶、还剩多少」，其余字段与计算无关 */
    private static PointsBucketEntity bucket(long bucketId, int remain) {
        PointsBucketEntity bucket = new PointsBucketEntity();
        bucket.setBucketId(bucketId);
        bucket.setRemain(remain);
        return bucket;
    }

    @Test
    @DisplayName("跨多个桶：前面的被扣光，最后一个只扣差额")
    void allocate_spansBuckets_drainsPrefixAndTakesOnlyTheGapFromTheLast() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket(1L, 20), bucket(2L, 30), bucket(3L, 50)), 70);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("前面的桶按 FIFO 逐个扣光").containsExactly(1L, 2L);
        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getAmount)
                .as("扣光的桶各扣掉自己的全部余额").containsExactly(20, 30);
        assertThat(plan.getPartialBucket().getBucketId()).as("差额落在第三个桶").isEqualTo(3L);
        assertThat(plan.getPartialBucket().getAmount()).as("只扣 70 - 20 - 30").isEqualTo(20);
    }

    @Test
    @DisplayName("恰好整笔扣完：全部归入被扣光的桶，没有部分扣的桶")
    void allocate_exactlyDrainsBuckets_leavesNoPartialBucket() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket(1L, 30), bucket(2L, 70), bucket(3L, 50)), 100);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("恰好扣满的两个桶都算扣光；额度已够，第三个桶不进方案").containsExactly(1L, 2L);
        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getAmount)
                .containsExactly(30, 70);
        assertThat(plan.getPartialBucket()).as("没有部分扣的桶").isNull();
    }

    @Test
    @DisplayName("跨 1 个桶：退化为一条部分扣，被扣光的桶为空")
    void allocate_fromSingleBucket_yieldsOnePartialAndNoDrained() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(List.of(bucket(1L, 100)), 60);

        assertThat(plan.getDrainedBuckets()).isEmpty();
        assertThat(plan.getPartialBucket().getBucketId()).isEqualTo(1L);
        assertThat(plan.getPartialBucket().getAmount()).isEqualTo(60);
    }

    @Test
    @DisplayName("跨 1 个桶恰好扣完：归入被扣光的桶，没有部分扣的桶")
    void allocate_fromSingleBucketExactlyDrained_yieldsOneDrainedAndNoPartial() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(List.of(bucket(1L, 60)), 60);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId).containsExactly(1L);
        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getAmount).containsExactly(60);
        assertThat(plan.getPartialBucket()).isNull();
    }

    @Test
    @DisplayName("部分扣的桶至多一个：碰到的桶再多，也只有最后一个部分是部分扣")
    void allocate_touchesManyBuckets_neverMoreThanOnePartialBucket() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket(1L, 10), bucket(2L, 10), bucket(3L, 10), bucket(4L, 10), bucket(5L, 90)), 55);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("前四个各 10 分被扣光；第五个是部分扣，不属于这个集合").containsExactly(1L, 2L, 3L, 4L);
        // 前四个扣光只到 40，第五个 remain 90 > 还差的 15：扣掉差额后归零、摊派即止
        assertThat(plan.getPartialBucket().getBucketId()).as("部分扣的就是第五个").isEqualTo(5L);
        assertThat(plan.getPartialBucket().getAmount()).isEqualTo(55 - 40);
        assertThat(plan.deductions()).hasSize(plan.getDrainedBuckets().size() + 1)
                .as("全部扣减 = 扣光的桶 + 一个部分扣的桶，不多不少");
    }

    @Test
    @DisplayName("落库顺序：扣光的桶在前、部分扣的桶在最后，逐条与 FIFO 一致")
    void deductions_keepFifoOrderWithPartialLast() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket(11L, 20), bucket(22L, 30), bucket(33L, 50)), 60);

        assertThat(plan.deductions()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("明细按 FIFO 逐桶记账，部分扣的那条排最后").containsExactly(11L, 22L, 33L);
        assertThat(plan.deductions()).extracting(PointsBucketDeductionVO::getAmount)
                .containsExactly(20, 30, 10);
    }

    @Test
    @DisplayName("可用桶凑不够整笔：抛不变量异常，绝不返回半份方案")
    void allocate_whenBucketsCannotCoverAmount_failsInsteadOfReturningPartialPlan() {
        assertThatThrownBy(() -> PointsDeductPlanVO.allocate(List.of(bucket(1L, 20), bucket(2L, 30)), 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("50")
                .hasMessageContaining("100");
    }
}
