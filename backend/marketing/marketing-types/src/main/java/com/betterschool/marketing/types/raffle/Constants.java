package com.betterschool.marketing.types.raffle;

/**
 * 抽奖域（draw_* 新模型）自有常量：Redis key 口径与规则链节点码。
 * 节点码与 draw_strategy.rule 的逗号分段值一致，同时是链节点的 bean 名（票 11 定，票 13 主链路直接引用）。
 */
public class Constants {

    /** 本域 Redis key 前缀（与 points / lottery 各占一段，避免同实例下串味） */
    public final static String RAFFLE_KEY_PREFIX = "bs:raffle:";

    /**
     * 概率表 key：{@code RAFFLE_TABLE_KEY + strategyId + ":default"} 与
     * {@code RAFFLE_TABLE_KEY + strategyId + ":weight:" + count}。
     * 结构为 Redisson RMap&lt;Integer 槽位, Long awardId&gt;，恒 10000 槽（概率 ×10000 口径），预热整体重建。
     */
    public final static String RAFFLE_TABLE_KEY = RAFFLE_KEY_PREFIX + "table:";

    /** 黑名单用户判定 key：{@code RAFFLE_BLACKLIST_USER_KEY + strategyId + ":" + userId}（bucket 存在即命中）；
     * 本域无黑名单用户表，该集合由风控侧写入，预热不负责 */
    public final static String RAFFLE_BLACKLIST_USER_KEY = RAFFLE_KEY_PREFIX + "blacklist:user:";

    /** 特型奖品位缓存 key：{@code RAFFLE_AWARD_KEY + strategyId + ":blacklist" / ":fallback"}（bucket 值 = awardId），
     * 预热时自 draw_strategy_award(type=blacklist/fallback) 各取一条灌入 */
    public final static String RAFFLE_AWARD_KEY = RAFFLE_KEY_PREFIX + "award:";

    /** 预热态标记 key：{@code RAFFLE_WARMUP_STATE_KEY + strategyId}（bucket 值 = 预热完成时刻 ISO 文本；
     * 不存在 = 降级态，走 DB 直算的入口归票 13 接） */
    public final static String RAFFLE_WARMUP_STATE_KEY = RAFFLE_KEY_PREFIX + "warmup:";

    /** 规则链节点码：黑名单拦截（draw_strategy_award.type = 'blacklist' 的奖品位） */
    public final static String RULE_NODE_BLACKLIST = "rule_blacklist";
    /** 规则链节点码：权重保底（draw_strategy_weight 命中档） */
    public final static String RULE_NODE_WEIGHT = "rule_weight";
    /** 规则链节点码：默认兜底（全量 normal 奖品位概率表） */
    public final static String RULE_NODE_DEFAULT = "rule_default";

    /**
     * 积分兑抽奖次数的单价（积分 / 次）。定价载体（旧模型活动表的价格列）已随
     * 旧模型删除，新模型没有对应的配置表，暂以常量承载（与旧种子同值 100）；配置化载体归票 14
     * 前端对齐时再定。对外展示在活动信息 DTO 的 {@code drawCountPrice}。
     */
    public final static int DRAW_COUNT_EXCHANGE_PRICE = 100;

    /** 默认概率表 key（RMap 槽位 → awardId，恒 10000 槽） */
    public static String defaultTableKey(Long strategyId) {
        return RAFFLE_TABLE_KEY + strategyId + ":default";
    }

    /** 权重档概率表 key（档内 award_rate 归一化，恒 10000 槽） */
    public static String weightTableKey(Long strategyId, Integer count) {
        return RAFFLE_TABLE_KEY + strategyId + ":weight:" + count;
    }
}
