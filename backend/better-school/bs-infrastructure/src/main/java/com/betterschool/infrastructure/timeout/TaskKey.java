package com.betterschool.infrastructure.timeout;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务的自然键 {@code (taskType, bizId)}：注册、结算、回调回报都用它指认一行。
 * <p>
 * equals/hashCode 有实际用途——回调回报的集合要按它做归属判断
 * （{@link CallbackReport#isSucceeded}），结算分桶时也拿它把「这一行的结果」翻成桶。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskKey {

    private String taskType;
    private String bizId;

    public static TaskKey of(String taskType, String bizId) {
        return new TaskKey(taskType, bizId);
    }
}
