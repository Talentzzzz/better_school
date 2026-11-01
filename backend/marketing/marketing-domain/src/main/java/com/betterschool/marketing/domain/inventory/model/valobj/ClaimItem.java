package com.betterschool.marketing.domain.inventory.model.valobj;

/**
 * 一件兑换确认（spec §4.5）：业务身份 + 数量，就是落库方法逐行 {@code INSERT IGNORE} 的那一行。
 *
 * <p><b>为什么不带 {@code segment}</b>：兑换确认只存在于实物段——虚拟段没有 {@code waiting}
 * （预扣直接 {@code surplus → issued}），也就没有确认动作。传一个恒为 {@code QUOTA} 的段参数
 * 只会多一条永远成立的分支（票 07 §4）。
 *
 * <p><b>为什么不带 {@code invId}</b>：{@code invId} 是合并器的分片键（{@code submitAndWait(invId, item)}），
 * 由调用方显式给、合并器不解析 payload；幂等键又只认 {@code (type = 2, bizType, bizId)}。
 * 把它塞进这里会有两个来源，反而要校验谁对。
 *
 * @param bizType {@code InventoryBizTypes.LOTTERY_CLAIM}
 * @param bizId   抽奖记录 id（= {@code draw_record.record_id}），不是独立兑换单号
 * @param quantity 本次转移的份数；一笔确认 = 一份实物出库，恒 &gt; 0
 */
public record ClaimItem(String bizType, long bizId, long quantity) {
}
