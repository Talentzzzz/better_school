package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发货状态机对一次「中奖事件」的处置计划（票 14）：{@code IRaffleShippingService.planShipAfterDraw}
 * 的返回，**只读不写**。调用方（case 层）按它决定要不要先补一次实物配额兑换确认，再调
 * {@code advanceShipAfterDraw} 真正推进状态。
 * <p>
 * 为什么把「计划」与「推进」拆成两步（而不是一次调用里全做掉）：实物档要在推进之前登记跨域的
 * 兑换确认，把「该确认什么」算出来放在只读的计划里，调用方就能在**不改状态**的前提下先做那一步。
 * 票 11 起确认已是一次本地写（不再同步判 {@code waiting_qty}），这个顺序不再承重，
 * 保留它是因为读起来仍是业务时序，且「记录仍在待兑换」仍是抽奖域自己的幂等判据。
 * <p>
 * {@code active = false} 表示本次无事可做（记录不存在 / 已终态 / 重复投递），此时
 * {@code quotaToConfirm} 必为 null。
 */
@Data
@AllArgsConstructor
public class ShippingPlanVO {

    private String recordId;
    /** 中奖落库时的奖品类型快照（VIRTUAL / PHYSICAL / DRAW_COUNT / …），决定推进哪些动作 */
    private String awardType;
    /**
     * 需要**先**做的实物配额兑换确认（登记 {@code type = 2} 明细，转移由库存域合并完成）；不需要时为 null。
     * 库存侧按 {@code (type, biz_type, biz_id)} 唯一键去重，重复登记按成功回放；抽奖域侧的重复投递
     * 由状态起点挡住（记录不在待兑换就不会走到这里）。
     */
    private QuotaConfirmVO quotaToConfirm;
    /** 是否真的有事可做；false 时调用方直接返回 */
    private boolean active;

    /**
     * 一次实物配额兑换确认的意图（库存业务 ID + 确认量）。
     * 抽奖域自己的值对象，不引用库存域的 {@code IInventoryQuotaService} 签名。
     */
    @Data
    @AllArgsConstructor
    public static class QuotaConfirmVO {
        /** 实物营销配额业务 ID（= draw_record.inv_id） */
        private String invId;
        /** 本次确认份数（一抽一份） */
        private long quantity;
    }
}
