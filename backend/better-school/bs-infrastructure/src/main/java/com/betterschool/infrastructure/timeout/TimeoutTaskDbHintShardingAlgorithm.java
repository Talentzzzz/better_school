package com.betterschool.infrastructure.timeout;

import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingValue;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 超时任务的 hint 分片算法·<b>库维</b>：吃 {@code HintManager#addDatabaseShardingValue} 塞进来的
 * 全局槽位号 {@code g}，按 {@code db = g / TABLES_PER_DB} 返回库维数据源名（{@code ds0..}）。
 * <p>
 * 这是「库 × 表」两维表达的库维一半（用户 2026-10-16 拍板保留表达）。本期 DB_COUNT 恒为 1、
 * 全部落 {@code ds0}——<b>不得配置第二个库</b>：超时行落到别的库 = 与业务写入不再同一条连接 =
 * 同事务前提失效（硬约束，见 TimeoutConstants 与架构文档）。表维见
 * {@link TimeoutTaskTableHintShardingAlgorithm}，槽位分解单点在 {@link TimeoutTaskShardSlots}。
 * <p>
 * 类名被 {@code sharding.yaml} / {@code sharding-test.yaml} 的 {@code algorithmClassName} 按 FQCN 引用
 * ——改包名/改名必须同步两份 YAML。
 */
public class TimeoutTaskDbHintShardingAlgorithm implements HintShardingAlgorithm<Integer> {

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         HintShardingValue<Integer> shardingValue) {
        Set<String> targets = new LinkedHashSet<>();
        for (Integer slot : shardingValue.getValues()) {
            if (slot == null) {
                continue;
            }
            String targetDataSource = TimeoutTaskShardSlots.dataSourceNameOf(slot);
            availableTargetNames.stream()
                    .filter(targetDataSource::equals)
                    .findFirst()
                    .ifPresentOrElse(targets::add, () -> {
                        throw new IllegalStateException("timeout_task 库维路由失败：槽位 " + slot
                                + " 需要数据源 " + targetDataSource + "，但可用数据源只有 " + availableTargetNames
                                + "——DB_COUNT > 1 未配置（且按硬约束不得启用，见 TimeoutConstants）或 YAML 配置漂移");
                    });
        }
        return targets;
    }
}
