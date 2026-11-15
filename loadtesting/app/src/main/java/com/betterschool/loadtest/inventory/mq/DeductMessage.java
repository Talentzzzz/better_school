package com.betterschool.loadtest.inventory.mq;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * redis_mq 策略的消息体：一条扣减一条消息，消费端逐条扣台账。
 */
public final class DeductMessage {

    private final String runId;
    private final long skuId;
    private final int bucketId;
    private final int qty;
    private final String orderNo;

    @JsonCreator
    public DeductMessage(@JsonProperty("runId") String runId,
                         @JsonProperty("skuId") long skuId,
                         @JsonProperty("bucketId") int bucketId,
                         @JsonProperty("qty") int qty,
                         @JsonProperty("orderNo") String orderNo) {
        this.runId = runId;
        this.skuId = skuId;
        this.bucketId = bucketId;
        this.qty = qty;
        this.orderNo = orderNo;
    }

    @JsonProperty("runId")
    public String runId() {
        return runId;
    }

    @JsonProperty("skuId")
    public long skuId() {
        return skuId;
    }

    @JsonProperty("bucketId")
    public int bucketId() {
        return bucketId;
    }

    @JsonProperty("qty")
    public int qty() {
        return qty;
    }

    @JsonProperty("orderNo")
    public String orderNo() {
        return orderNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeductMessage other)) return false;
        return skuId == other.skuId
                && bucketId == other.bucketId
                && qty == other.qty
                && Objects.equals(runId, other.runId)
                && Objects.equals(orderNo, other.orderNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, skuId, bucketId, qty, orderNo);
    }

    @Override
    public String toString() {
        return "DeductMessage[runId=" + runId + ", skuId=" + skuId + ", bucketId=" + bucketId + ", qty=" + qty
                + ", orderNo=" + orderNo + "]";
    }
}
