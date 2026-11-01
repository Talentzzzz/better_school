package com.betterschool.marketing.domain.raffle.model.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抽奖策略奖品位（draw_strategy_award）：概率池的配置行。
 * <p>
 * 概率口径：{@code awardRate} 是 DECIMAL(6,4)，×10000 即概率表槽位数；同一策略内 type=normal
 * 各行 award_rate 合计恒 = 1.0000（预热时校验）。type=blacklist / fallback 的行不进默认概率表，
 * 由预热单独缓存其 awardId 供黑名单节点 / 库存兜底（票 13）取用。
 */
@Data
public class DrawStrategyAwardEntity {

    private Long id;
    private Long strategyId;
    /** 奖品 ID（策略内唯一） */
    private Long awardId;
    private String awardName;
    private String awardDesc;
    /** VIRTUAL(虚拟资产) / PHYSICAL(实物资产) / THANKS(谢谢参与占位，rate 计入概率池、无库存) */
    private String awardType;
    /** 中奖概率，如 0.0500 = 5% */
    private BigDecimal awardRate;
    /** 奖项层级（1 等奖 = 1，越大越低） */
    private Integer level;
    /** 关联库存业务 ID（0 = 无库存/不限量） */
    private Long invId;
    /** 奖品位类型 normal / fallback / blacklist */
    private String type;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
