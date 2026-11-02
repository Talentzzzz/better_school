package com.betterschool.marketing.domain.raffle.service.warmup;

/**
 * 抽奖决策侧表预热：把概率 / 权重配置按 ×10000 口径灌入 Redis（与责任链同属决策侧，
 * 主链路只管用，故与责任链同票落地）。幂等可重跑——整体重建概率表 + 失效规则链缓存，
 * 配置变更后重预热即生效。
 */
public interface IRaffleWarmupService {

    /** 按活动预热（活动 → 策略 → 概率表 + 权重表 + 特型奖品位 + 预热态标记） */
    void warmupByActivityId(String activityId);

    /** 按策略预热（已知策略 ID 时跳过活动查表） */
    void warmupByStrategyId(String strategyId);

    /** 预热态可查：true = 预热态；false = 降级态（票 13 接 DB 直算降级入口） */
    boolean isWarmed(String strategyId);
}
