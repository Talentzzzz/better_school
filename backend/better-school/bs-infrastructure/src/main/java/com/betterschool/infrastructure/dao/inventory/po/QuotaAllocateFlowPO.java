package com.betterschool.infrastructure.dao.inventory.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配额池分配流水行（inventory_allocation_flow）：池侧唯一的转出流水（award-inventory 两层模型里
 * 池 {@code surplus_qty} 没有守恒等式，对账靠本表：池转出量 = SUM(quantity)；不限量池 surplus 不减、流水照记）。
 * <p>
 * {@code uk_alloc_biz} 是分配幂等的池侧裁决键：撞分配单号 = 本单已执行，按成功回放。
 */
@Data
public class QuotaAllocateFlowPO {

    private Long id;
    /** 来源池段：QUOTA / VIRTUAL（{@code InventorySegmentVO#getCode()}） */
    private String poolSegment;
    /** 来源池台账业务 ID */
    private String poolInvId;
    /** 目标奖品位 id（draw_strategy_award_inventory.award_id） */
    private String awardId;
    /** 本次分配份数 */
    private Long quantity;
    /** 分配单号（幂等锚点） */
    private String allocBizId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
