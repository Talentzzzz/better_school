package com.betterschool.infrastructure.dao.award.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 奖品库存流转明细行（award_inventory_detail）：
 * 预扣（{@code flow_type = 1}）/ 兑换确认（{@code flow_type = 2}）共用一张表，
 * 幂等裁决完全交给 {@code uk_award_biz (award_id, biz_type, biz_id)}。
 */
@Data
public class AwardInventoryDetailPO {

    private Long id;
    /** 奖品位 id（= 台账行），幂等键前缀 */
    private String awardId;
    /** 1 预扣 / 2 兑换确认（仅 PHYSICAL） */
    private Integer flowType;
    /** 本行份数 */
    private Long quantity;
    /** LOTTERY_DRAW / LOTTERY_CLAIM */
    private String bizType;
    /** 抽奖记录 id（预扣与确认共用） */
    private String bizId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
