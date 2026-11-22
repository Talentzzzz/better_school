package com.betterschool.domain.points.model.valobj;

import com.betterschool.domain.points.model.entity.PointsBucketEntity;
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

    /** 方案只关心「哪个桶、还剩多少」，其余字段与计算无关；bucketId 已随 uuid-biz-id 票 07 转 UUID 字符串 */
    private static PointsBucketEntity bucket(String bucketId, int remain) {
        PointsBucketEntity bucket = new PointsBucketEntity();
        bucket.setBucketId(bucketId);
        bucket.setRemain(remain);
        return bucket;
    }

    @Test
    @DisplayName("跨多个桶：前面的被扣光，最后一个只扣差额")
    void allocate_spansBuckets_drainsPrefixAndTakesOnlyTheGapFromTheLast() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket("B1", 20), bucket("B2", 30), bucket("B3", 50)), 70);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("前面的桶按 FIFO 逐个扣光").containsExactly("B1", "B2");
        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getAmount)
                .as("扣光的桶各扣掉自己的全部余额").containsExactly(20, 30);
        assertThat(plan.getPartialBucket().getBucketId()).as("差额落在第三个桶").isEqualTo("B3");
        assertThat(plan.getPartialBucket().getAmount()).as("只扣 70 - 20 - 30").isEqualTo(20);
    }

    @Test
    @DisplayName("恰好整笔扣完：全部归入被扣光的桶，没有部分扣的桶")
    void allocate_exactlyDrainsBuckets_leavesNoPartialBucket() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket("B1", 30), bucket("B2", 70), bucket("B3", 50)), 100);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("恰好扣满的两个桶都算扣光；额度已够，第三个桶不进方案").containsExactly("B1", "B2");
        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getAmount)
                .containsExactly(30, 70);
        assertThat(plan.getPartialBucket()).as("没有部分扣的桶").isNull();
    }

    @Test
    @DisplayName("跨 1 个桶：退化为一条部分扣，被扣光的桶为空")
    void allocate_fromSingleBucket_yieldsOnePartialAndNoDrained() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(List.of(bucket("B1", 100)), 60);

        assertThat(plan.getDrainedBuckets()).isEmpty();
        assertThat(plan.getPartialBucket().getBucketId()).isEqualTo("B1");
        assertThat(plan.getPartialBucket().getAmount()).isEqualTo(60);
    }

    @Test
    @DisplayName("跨 1 个桶恰好扣完：归入被扣光的桶，没有部分扣的桶")
    void allocate_fromSingleBucketExactlyDrained_yieldsOneDrainedAndNoPartial() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(List.of(bucket("B1", 60)), 60);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId).containsExactly("B1");
        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getAmount).containsExactly(60);
        assertThat(plan.getPartialBucket()).isNull();
    }

    @Test
    @DisplayName("部分扣的桶至多一个：碰到的桶再多，也只有最后一个部分是部分扣")
    void allocate_touchesManyBuckets_neverMoreThanOnePartialBucket() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket("B1", 10), bucket("B2", 10), bucket("B3", 10), bucket("B4", 10), bucket("B5", 90)), 55);

        assertThat(plan.getDrainedBuckets()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("前四个各 10 分被扣光；第五个是部分扣，不属于这个集合").containsExactly("B1", "B2", "B3", "B4");
        // 前四个扣光只到 40，第五个 remain 90 > 还差的 15：扣掉差额后归零、摊派即止
        assertThat(plan.getPartialBucket().getBucketId()).as("部分扣的就是第五个").isEqualTo("B5");
        assertThat(plan.getPartialBucket().getAmount()).isEqualTo(55 - 40);
        assertThat(plan.deductions()).hasSize(plan.getDrainedBuckets().size() + 1)
                .as("全部扣减 = 扣光的桶 + 一个部分扣的桶，不多不少");
    }

    @Test
    @DisplayName("落库顺序：扣光的桶在前、部分扣的桶在最后，逐条与 FIFO 一致")
    void deductions_keepFifoOrderWithPartialLast() {
        PointsDeductPlanVO plan = PointsDeductPlanVO.allocate(
                List.of(bucket("B11", 20), bucket("B22", 30), bucket("B33", 50)), 60);

        assertThat(plan.deductions()).extracting(PointsBucketDeductionVO::getBucketId)
                .as("明细按 FIFO 逐桶记账，部分扣的那条排最后").containsExactly("B11", "B22", "B33");
        assertThat(plan.deductions()).extracting(PointsBucketDeductionVO::getAmount)
                .containsExactly(20, 30, 10);
    }

    @Test
    @DisplayName("可用桶凑不够整笔：抛不变量异常，绝不返回半份方案")
    void allocate_whenBucketsCannotCoverAmount_failsInsteadOfReturningPartialPlan() {
        assertThatThrownBy(() -> PointsDeductPlanVO.allocate(List.of(bucket("B1", 20), bucket("B2", 30)), 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("50")
                .hasMessageContaining("100");
    }
}
