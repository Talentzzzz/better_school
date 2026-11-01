package com.betterschool.marketing.types.inventory;

/**
 * 库存明细的业务类型（两张明细表的 {@code biz_type} 列）：回答「这一行扣减属于哪次业务」。
 * <p>
 * 取值口径（spec §7.1）：{@link #LOTTERY_DRAW} = 抽奖预扣；{@link #LOTTERY_CLAIM} = 兑换确认。
 * 两个业务键都是抽奖记录的 {@code draw_record.record_id}（{@code biz_id} 列）；抽奖域不认识库存的表列，
 * 这两个值由库存侧的端口实现 / 域服务填。
 */
public final class InventoryBizTypes {

    /** 抽奖预扣：两张明细表的预扣行都用它 */
    public final static String LOTTERY_DRAW = "LOTTERY_DRAW";

    /** 兑换确认：实物明细 {@code type = 2} 的行（与预扣行共用 {@code biz_id = recordId}，靠 {@code type} 区分） */
    public final static String LOTTERY_CLAIM = "LOTTERY_CLAIM";

    private InventoryBizTypes() {
    }
}
