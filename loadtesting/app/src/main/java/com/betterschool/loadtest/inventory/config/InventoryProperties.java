package com.betterschool.loadtest.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 压测工程的可调参数，全部可通过环境变量覆盖（见 application.yml）。
 */
@ConfigurationProperties(prefix = "inventory")
public class InventoryProperties {

    /** 默认压测的 SKU（单热点） */
    private long defaultSkuId = 1001L;

    /** 库为空时首个 SKU 的初始库存 */
    private long defaultStock = 10000L;

    /** 初始分桶数（reset 接口可覆盖） */
    private int bucketCount = 1;

    /** 请求未指定 strategy 时的默认策略 */
    private String defaultStrategy = "merge";

    private Merge merge = new Merge();

    public long getDefaultSkuId() {
        return defaultSkuId;
    }

    public void setDefaultSkuId(long defaultSkuId) {
        this.defaultSkuId = defaultSkuId;
    }

    public long getDefaultStock() {
        return defaultStock;
    }

    public void setDefaultStock(long defaultStock) {
        this.defaultStock = defaultStock;
    }

    public int getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(int bucketCount) {
        this.bucketCount = bucketCount;
    }

    public String getDefaultStrategy() {
        return defaultStrategy;
    }

    public void setDefaultStrategy(String defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }

    public Merge getMerge() {
        return merge;
    }

    public void setMerge(Merge merge) {
        this.merge = merge;
    }

    public static class Merge {

        /** 合并唤醒去重窗口：窗口内同一桶只发一条延迟消息 */
        private long dedupeWindowMs = 1000L;

        /** RocketMQ 延迟等级，1 = 1s */
        private int delayLevel = 1;

        /** 一条唤醒消息最多合并几个时间格（1 = 完全对齐线上「只合最老一格」语义） */
        private int maxCellsPerWake = 1;

        /** 兜底扫描间隔：捞陈旧未合并格补发唤醒 */
        private long fallbackIntervalMs = 5000L;

        /** 未合并格超过多少秒算陈旧 */
        private int staleSeconds = 2;

        /** 兜底扫描一次最多补几个桶 */
        private int scanLimit = 50;

        public long getDedupeWindowMs() {
            return dedupeWindowMs;
        }

        public void setDedupeWindowMs(long dedupeWindowMs) {
            this.dedupeWindowMs = dedupeWindowMs;
        }

        public int getDelayLevel() {
            return delayLevel;
        }

        public void setDelayLevel(int delayLevel) {
            this.delayLevel = delayLevel;
        }

        public int getMaxCellsPerWake() {
            return maxCellsPerWake;
        }

        public void setMaxCellsPerWake(int maxCellsPerWake) {
            this.maxCellsPerWake = maxCellsPerWake;
        }

        public long getFallbackIntervalMs() {
            return fallbackIntervalMs;
        }

        public void setFallbackIntervalMs(long fallbackIntervalMs) {
            this.fallbackIntervalMs = fallbackIntervalMs;
        }

        public int getStaleSeconds() {
            return staleSeconds;
        }

        public void setStaleSeconds(int staleSeconds) {
            this.staleSeconds = staleSeconds;
        }

        public int getScanLimit() {
            return scanLimit;
        }

        public void setScanLimit(int scanLimit) {
            this.scanLimit = scanLimit;
        }
    }
}
