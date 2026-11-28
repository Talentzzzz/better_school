package com.betterschool.types.award;

/**
 * 奖品域自有常量：明细表的流转类型分工。
 * <p>
 * 票 06 起 Redis 桶层整层删除（桶 key / 去重 key / Lua 返回码随之消失），票 08 起旧合并机制
 * （{@code bs-inventory-merge} topic / 消费组 / 合并消息 / 兜底常量）随之删除：台账三列是唯一真相，
 * 预扣与确认由进程内合并器一组一事务直接落账，不再有消息驱动的合并。
 * <p>
 * 预扣 / 确认统一打奖品库存明细表（{@code award_inventory_detail.flow_type}）。
 */
public final class Constants {

    /** 明细流转 {@code type/flow_type}：1-预扣结转（落库即进 waiting / issued） */
    public final static int DETAIL_TYPE_DEDUCT = 1;

    /** 明细流转 {@code type/flow_type}：2-兑换确认（确认同步转移 waiting_qty → issued_qty） */
    public final static int DETAIL_TYPE_CLAIM = 2;

    private Constants() {
    }
}
