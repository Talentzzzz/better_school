package com.betterschool.infrastructure.timeout;

import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingValue;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 超时任务的 hint 分片算法·<b>表维</b>：吃 {@code HintManager#addTableShardingValue} 塞进来的
 * 全局槽位号 {@code g}，按 {@code table = g % TABLES_PER_DB} 返回物理分表名。
 * <p>
 * 槽位 → 表名走 {@link TimeoutTaskShardSlots#tableNameOf}，与仓储层按 bizId 算槽位是同一个类
 * ——扫描按槽位直达的那张表，必须是写入按 bizId 算出的同一张表（核心约束，单点函数收敛在
 * TimeoutTaskShardSlots）。库维见 {@link TimeoutTaskDbHintShardingAlgorithm}。
 * <p>
 * 类名被 {@code sharding.yaml} / {@code sharding-test.yaml} 的 {@code algorithmClassName} 按 FQCN 引用
 * ——改包名/改名必须同步两份 YAML。
 */
public class TimeoutTaskTableHintShardingAlgorithm implements HintShardingAlgorithm<Integer> {

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         HintShardingValue<Integer> shardingValue) {
        Set<String> targets = new LinkedHashSet<>();
        for (Integer slot : shardingValue.getValues()) {
            if (slot == null) {
                continue;
            }
            String targetTable = TimeoutTaskShardSlots.tableNameOf(slot);
            availableTargetNames.stream()
                    .filter(name -> name.equals(targetTable) || name.endsWith("." + targetTable))
                    .findFirst()
                    .ifPresentOrElse(targets::add, () -> {
                        throw new IllegalStateException("timeout_task 表维路由失败：槽位 " + slot
                                + " 需要物理表 " + targetTable + "，但 actualDataNodes 里没有它（YAML 配置漂移）");
                    });
        }
        return targets;
    }
}
