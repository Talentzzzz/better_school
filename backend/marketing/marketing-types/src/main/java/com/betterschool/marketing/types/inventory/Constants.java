package com.betterschool.marketing.types.inventory;

/**
 * 库存域自有常量：明细表的流转类型分工。
 * <p>
 * 票 06 起 Redis 桶层整层删除（桶 key / 去重 key / Lua 返回码随之消失），票 08 起旧合并机制
 * （{@code bs-inventory-merge} topic / 消费组 / 合并消息 / 兜底常量）随之删除：台账三列是唯一真相，
 * 预扣与确认由进程内合并器一组一事务直接落账，不再有消息驱动的合并。
 * <p>
 * award-inventory 两层模型起：预扣 / 确认 / 分配统一打奖品库存明细表
 * （{@code draw_strategy_award_inventory_detail.flow_type}）；池侧两张明细表保留但不再新增写入面。
 */
public final class Constants {

    /** 奖品库存明细 {@code flow_type}：0-分配转入（配额池 surplus → 奖品库存 surplus） */
    public final static int FLOW_TYPE_ALLOCATE = 0;

    /** 明细流转 {@code type/flow_type}：1-预扣结转（落库即进 waiting / issued） */
    public final static int DETAIL_TYPE_DEDUCT = 1;

    /** 明细流转 {@code type/flow_type}：2-兑换确认（确认同步转移 waiting_qty → issued_qty） */
    public final static int DETAIL_TYPE_CLAIM = 2;

    private Constants() {
    }
}
