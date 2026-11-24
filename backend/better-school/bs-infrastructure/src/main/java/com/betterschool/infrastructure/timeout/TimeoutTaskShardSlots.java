package com.betterschool.infrastructure.timeout;

import java.util.Locale;

/**
 * 槽位公式：写入与扫描两个方向<b>必须</b>共用这一个函数，否则扫描会漏掉写入落在别的分表上的行
 * ——这是超时组件内嵌化的核心约束，不是优化点。
 * <p>
 * hash 输入是 {@code biz_id}（不是 {@code (task_type, biz_id)}）：同一业务对象的多种超时
 * （PAY_TIMEOUT/LOCK_TIMEOUT）与多轮次（复合 bizId 的 {@code :seq} 轮次）落同一张表；
 * {@code uk_type_biz (task_type, biz_id)} 的全局唯一性不受影响（同键行永远同表，uk 在表内依旧唯一）。
 * <p>
 * 哈希用 {@link String#hashCode()}：算法由 JLS 写死（{@code s[0]*31^(n-1) + ...}），
 * 跨 JVM、跨版本稳定，正是「同 key 永远同表」需要的性质。
 */
public final class TimeoutTaskShardSlots {

    private TimeoutTaskShardSlots() {
    }

    /** 槽位 = {@code floorMod(hash(biz_id), TOTAL)}（TOTAL = dbCount × tablesPerDb = 16） */
    public static int slotOf(String bizId) {
        return Math.floorMod(bizId.hashCode(), TimeoutConstants.TOTAL_SLOTS);
    }

    /** 槽位 g → 库维下标：{@code db = g / TABLES_PER_DB}（本期 DB_COUNT = 1，恒 0） */
    public static int dbIndexOf(int slot) {
        return slot / TimeoutConstants.TABLES_PER_DB;
    }

    /** 槽位 g → 表维下标：{@code table = g % TABLES_PER_DB} */
    public static int tableIndexOf(int slot) {
        return slot % TimeoutConstants.TABLES_PER_DB;
    }

    /** 槽位 g → 库维数据源名（YAML dataSources 的键，{@code ds0..ds<dbCount-1>}） */
    public static String dataSourceNameOf(int slot) {
        return TimeoutConstants.DATA_SOURCE_PREFIX + dbIndexOf(slot);
    }

    /**
     * 槽位 g → 物理表名（表维两位零填充，与 DDL 的 {@code timeout_task_00..15} 对齐）。
     * 注意物理名只给 ShardingSphere 的算法与运维用，业务/测试 SQL 一律写逻辑表 {@code timeout_task}。
     */
    public static String tableNameOf(int slot) {
        return TimeoutConstants.TASK_LOGIC_TABLE + "_"
                + String.format(Locale.ROOT, "%02d", tableIndexOf(slot));
    }
}
