package com.betterschool.marketing.types.inventory;

/**
 * 库存域自有常量：实物明细的 {@code type} 分工。
 * <p>
 * 票 06 起 Redis 桶层整层删除（桶 key / 去重 key / Lua 返回码随之消失），票 08 起旧合并机制
 * （{@code bs-inventory-merge} topic / 消费组 / 合并消息 / 兜底常量）随之删除：台账三列是唯一真相，
 * 预扣与确认由进程内合并器一组一事务直接落账，不再有消息驱动的合并。
 */
public final class Constants {

    /** 实物明细 {@code type}：1-预扣结转（票 06 起落库即进 waiting / issued） */
    public final static int DETAIL_TYPE_DEDUCT = 1;

    /** 实物明细 {@code type}：2-兑换确认（票 07 起确认同步转移 waiting_qty → issued_qty） */
    public final static int DETAIL_TYPE_CLAIM = 2;

    private Constants() {
    }
}
