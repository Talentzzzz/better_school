package com.betterschool.infrastructure.timeout;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 结算三桶的<b>写出面</b>：一桶一条批量条件 UPDATE，返回实际改到的行数。
 * <p>
 * 开这条缝只为一件事——{@link TimeoutSettler} 的分桶与退避公式是纯函数的，离线单测拿它做个
 * 3 方法的假仓储就能数「一批写了几次」，不必造出整张表。生产侧唯一实现是 {@link TimeoutTaskStore}，
 * 没有第二份 SQL，也<b>不要</b>为它再写别的实现。
 */
public interface ITimeoutBucketWriter {

    /** 成功桶：整桶一条 UPDATE 置 SUCCESS 终态（带未终态守卫） */
    int markSuccessBatch(int slot, String taskType, List<String> bizIdList);

    /**
     * 重试桶：整桶一条 UPDATE 置 RETRYING、{@code retry_count + 1}，逐行后移到各自退避时刻。
     * <b>键序必须在语句里两次出现时保持一致</b>——错位会把退避写到别的行上。
     */
    int markRetryBatch(int slot, String taskType, LinkedHashMap<String, Integer> backoffSeconds);

    /** 死信桶：整桶一条 UPDATE 置 FAILED 终态、{@code retry_count + 1}（带未终态守卫） */
    int markDeadBatch(int slot, String taskType, List<String> bizIdList);
}
