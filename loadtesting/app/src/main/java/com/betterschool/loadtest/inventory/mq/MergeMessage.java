package com.betterschool.loadtest.inventory.mq;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * merge 策略的唤醒消息：只带「哪个桶」，不带任何一笔业务数据 ——
 * 合并的输入是明细表里的时间格，不是消息本身。这是它能把写放大从「每条一次」降到「每秒一次」的原因。
 */
public final class MergeMessage {

    private final String runId;
    private final long skuId;
    private final int bucketId;

    @JsonCreator
    public MergeMessage(@JsonProperty("runId") String runId,
                        @JsonProperty("skuId") long skuId,
                        @JsonProperty("bucketId") int bucketId) {
        this.runId = runId;
        this.skuId = skuId;
        this.bucketId = bucketId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MergeMessage other)) return false;
        return skuId == other.skuId
                && bucketId == other.bucketId
                && Objects.equals(runId, other.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, skuId, bucketId);
    }

    @Override
    public String toString() {
        return "MergeMessage[runId=" + runId + ", skuId=" + skuId + ", bucketId=" + bucketId + "]";
    }
}
