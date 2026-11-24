package com.betterschool.infrastructure.timeout;

/**
 * 超时组件的分表规格常量（原独立超时工程的 Constants，随组件内嵌迁入）。
 * <p>
 * 「库 × 表」两维表达（用户 2026-10-16 拍板保留）：槽位是<b>全局展平编号</b> {@code g}（0..TOTAL-1），
 * {@code db = g / TABLES_PER_DB}、{@code table = g % TABLES_PER_DB}。
 * 本期 {@link #DB_COUNT} 恒为 1——16 个槽位全部落在业务自己的 schema 里。
 * <p>
 * ⚠️ 硬约束：{@code DB_COUNT > 1} 与「超时行与业务写入同事务」<b>互斥</b>（业务行留在原库、
 * 超时行按 hash 落到另一个库时同事务立刻失效）。这里保留的是<b>表达</b>，不是<b>开关</b>：
 * 要启用的前提是业务表也按同一分片键一起分库（见 backend/docs/architecture/timeout.md §0）。
 * <p>
 * 为什么放在组件包里而不是 {@code types}：这几个数是<b>这台的机器规格</b>（分表数、槽位数、数据源前缀），
 * 只被本组件与 sharding.yaml 消费，不属于跨模块共享的通用常量。
 */
public final class TimeoutConstants {

    /** 逻辑表名：所有读写 SQL 都写它，物理分表由 ShardingSphere 的 hint 路由决定 */
    public static final String TASK_LOGIC_TABLE = "timeout_task";

    /** 每库分表数：timeout_task_00..15（两位零填充，与 DDL 对齐） */
    public static final int TABLES_PER_DB = 16;

    /** 库数：本期恒 1（不得启用 >1，理由见类注释的硬约束） */
    public static final int DB_COUNT = 1;

    /** 全局槽位总数 = dbCount × tablesPerDb = 16；扫描认领只看这个数（g % shardTotal == shardIndex && g < TOTAL） */
    public static final int TOTAL_SLOTS = DB_COUNT * TABLES_PER_DB;

    /** 库维数据源名前缀：槽位 g 的库 = ds + (g / TABLES_PER_DB)，YAML 的 dataSources 键按此命名 */
    public static final String DATA_SOURCE_PREFIX = "ds";

    private TimeoutConstants() {
    }
}
