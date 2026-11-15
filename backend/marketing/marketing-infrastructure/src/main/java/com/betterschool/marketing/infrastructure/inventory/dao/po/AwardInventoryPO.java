package com.betterschool.marketing.infrastructure.inventory.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 奖品库存统一台账行（draw_strategy_award_inventory）：抽奖直接扣减的账户，按 {@code award_id} 1:1，
 * 由配额池「分配」而来（award-inventory 两层模型的下层）。
 * <p>
 * {@code state} 沿用原台账语义但落 TINYINT（0 open / 1 exhausted / 2 closed），
 * 仓储翻成 {@link com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO} 的
 * 字符串 state（域服务「非 open 走重放预检」的口径不变）。
 */
@Data
public class AwardInventoryPO {

    private Long id;
    /** 奖品位 id（draw_strategy_award.award_id） */
    private String awardId;
    /** PHYSICAL 实物（两段式）/ VIRTUAL 虚拟（waiting 恒 0） */
    private String type;
    /** 可扣余量；-1 = 不限量 */
    private Long surplusQty;
    /** 已预占未确认（仅 PHYSICAL 使用，VIRTUAL 恒 0） */
    private Long waitingQty;
    /** 已发放 */
    private Long issuedQty;
    /** 单次发放面额（仅 VIRTUAL：POINTS 积分 / DRAW_COUNT 次数；实物 NULL） */
    private Long valueQty;
    /** 0 open / 1 exhausted / 2 closed */
    private Integer state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
