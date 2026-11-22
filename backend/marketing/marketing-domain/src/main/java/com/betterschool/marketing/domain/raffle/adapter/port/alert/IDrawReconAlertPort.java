package com.betterschool.marketing.domain.raffle.adapter.port.alert;

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
     * 通知幂等键由消费侧拼装为 {@code MARKETING:RECON:<roundNo>:<checkType>:<bizId>}，
     * 同轮次同检查同单号的重复点名只落一条通知。
     *
     * @param roundNo       对账轮次号（差错表 round_no）
     * @param checkType     检查类型（差错表 check_type，如 B1 台账守恒 / B3 预扣守恒 / 正向配对 / 反向配对 / 孤儿单死信）
     * @param scopeKey      定位键（award_id 或 record_id；无自然定位键的检查传 null）
     * @param bizId         定位单号（= 差错表 biz_id；消费侧 dedupKey 的一员，不可为空）
     * @param expectSummary 期望值快照摘要（公式左侧，如「waiting + issued = 25」）
     * @param actualSummary 实际值快照摘要（公式右侧实读数）
     * @param alertText     告警文案（完整话术，消费侧直接呈现；null 时消费侧按其余字段拼装）
     */
    void sendReconAlert(String roundNo, String checkType, String scopeKey, String bizId,
                        String expectSummary, String actualSummary, String alertText);
}
