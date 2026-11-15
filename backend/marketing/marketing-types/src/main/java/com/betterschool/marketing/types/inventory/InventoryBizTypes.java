package com.betterschool.marketing.types.inventory;

/**
 * 库存明细的业务类型（奖品库存统一明细表 {@code draw_strategy_award_inventory_detail.biz_type} 列）：
 * 回答「这一行流转属于哪次业务」。
 * <p>
 * 取值口径（spec §7.1）：{@link #LOTTERY_DRAW} = 抽奖预扣；{@link #LOTTERY_CLAIM} = 兑换确认；
 * {@link #AWARD_ALLOCATE} = 配额池 → 奖品库存的分配转入（{@code biz_id} = 分配单号）。
 * 抽奖两个业务键都是抽奖记录的 {@code draw_record.record_id}（{@code biz_id} 列）；抽奖域不认识
 * 库存的表列，这些值由库存侧的端口实现 / 域服务填。
 */
public final class InventoryBizTypes {

    /** 抽奖预扣：奖品库存统一明细表的预扣行（{@code flow_type = 1}）都用它 */
    public final static String LOTTERY_DRAW = "LOTTERY_DRAW";

    /** 兑换确认：预扣行与确认行共用 {@code biz_id = recordId}，靠 {@code flow_type}（1/2）区分 */
    public final static String LOTTERY_CLAIM = "LOTTERY_CLAIM";

    /** 分配转入：{@code flow_type = 0} 的行，{@code biz_id} = 分配单号（分配幂等的奖品侧裁决键） */
    public final static String AWARD_ALLOCATE = "AWARD_ALLOCATE";

    private InventoryBizTypes() {
    }
}
