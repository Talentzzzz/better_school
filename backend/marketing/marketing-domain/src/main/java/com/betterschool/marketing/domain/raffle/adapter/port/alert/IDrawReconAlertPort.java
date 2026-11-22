package com.betterschool.marketing.domain.raffle.adapter.port.alert;

import com.betterschool.marketing.types.common.DrawReconAlertMessage;

/**
 * 抽奖域对账轮 → 通知通路的告警端口（CLAUDE.md §1：跨域写组合不在域服务间直接注入）。
 * 实现在 infrastructure，经 outbox 落库 + RocketMQ 投递（复用既有营销通知链路，
 * 单体消费后落 BOTTOM_SYSTEM 维护者告警通知）——对账服务不注入事务模板，
 * 故端口实现把「插 outbox 行」做成单行原子写（自带事务），不依赖业务事务包裹；
 * 首发失败由 outbox 扫描兜底重发，消费侧按 dedupKey 幂等。
 * <p>
 * 告警判据由调用方（对账服务）掌握：只有 CONFIRMED 差错与 DEAD 孤儿单才走到本端口，
 * PENDING（未经二次核对）不发——端口只管投递，不做分流判断。
 * 投递失败（outbox 落库失败）按异常上抛：告警丢失与差错登记的语义不同，
 * 下一轮对账仍会重新点名，调用方不该为它中断本轮轧账。
 */
public interface IDrawReconAlertPort {

    /**
     * 发送一条对账告警（经 outbox → RocketMQ {@code bs-marketing-recon-alert} → 单体消费落通知）。
     * 载荷即 wire 消息 {@link DrawReconAlertMessage} 本体（轮次、检查类型、定位键、期望/实际快照、
     * 告警文案）；通知幂等键由消费侧拼装为 {@code MARKETING:RECON:<roundNo>:<checkType>:<bizId>}，
     * 同轮次同检查同单号的重复点名只落一条通知。
     */
    void sendReconAlert(DrawReconAlertMessage alertMessage);
}
