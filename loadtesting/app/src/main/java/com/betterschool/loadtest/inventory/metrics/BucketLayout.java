package com.betterschool.loadtest.inventory.metrics;

import org.springframework.stereotype.Component;

/**
 * 运行期分桶数：以 DB 里实际的 bucket 行为准（reset 可以改分桶数）。
 * 策略按这个值循环扣桶，避免「配置写 1、DB 里有 4 个桶，只卖出 1/4」这类错位。
 */
@Component
public class BucketLayout {

    private volatile int count = 1;

    public int count() {
        return count;
    }

    public void set(int count) {
        this.count = Math.max(1, count);
    }
}
