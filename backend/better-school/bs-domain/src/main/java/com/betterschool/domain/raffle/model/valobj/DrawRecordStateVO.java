package com.betterschool.domain.raffle.model.valobj;

/**
 * draw_record.state 的四段状态（库值中文，HTTP 面英文映射归 trigger 层）。
 * 状态推进只有一处入口——{@link com.betterschool.domain.raffle.service.IRaffleShippingService}，
 * 且每推进一步同事务落一条 {@link DrawRecordActionVO} 动作明细。
 */
public final class DrawRecordStateVO {

    /** 待兑换：中奖落库后的起点（THANKS 档除外） */
    public final static String PENDING = "待兑换";
    /** 已兑换：兑换确认已受理、库存 waiting_qty → issued_qty 的那一步 */
    public final static String EXCHANGED = "已兑换";
    /** 已发货：实物奖已交付发出 */
    public final static String SHIPPED = "已发货";
    /** 已完成：终态（THANKS 落库即到此；虚拟档入账后到此；实物档确认收货后到此） */
    public final static String COMPLETED = "已完成";

    private DrawRecordStateVO() {
    }
}
