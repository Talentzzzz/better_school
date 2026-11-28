package com.betterschool.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 营销库存对账告警事件（topic {@code bs-marketing-recon-alert}）：生产方 = 抽奖域对账轮
 * （{@code drawReconScan}，发现 CONFIRMED 差错 / DEAD 孤儿单时点名），
 * 消费方 = 单体（落 BOTTOM_SYSTEM 维护者告警通知）。
 * <p>
 * 单体侧在 {@code bs-case} 的 {@code usecase/marketing} 下有一份同字段副本
 * （跨服务 wire 层有意各持一份，先例见 {@link LotteryDrawnMessage}）；通知落库由
 * {@code dedup_key = MARKETING:RECON:<roundNo>:<checkType>:<bizId>} 幂等——同轮次同检查
 * 同业务单的重复点名（job 重跑 / broker 重投）只落一条。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DrawReconAlertMessage {

    /** 对账轮次号（差错表 round_no）：定位到是哪一轮大考点的名 */
    private String roundNo;
    /** 检查类型（差错表 check_type，如 B3 预扣守恒 / 正向配对 / 反向配对 / 孤儿单死信） */
    private String checkType;
    /** 定位键 scope_key（award_id 或 record_id；无自然定位键的检查为空） */
    private String scopeKey;
    /** 定位单号 biz_id（= 差错表 biz_id，对账幂等键的一员） */
    private String bizId;
    /** 期望值快照摘要（公式左侧，如「waiting + issued = 25」） */
    private String expectSummary;
    /** 实际值快照摘要（公式右侧实读数，如「Σ预扣明细 = 24」） */
    private String actualSummary;
    /** 告警文案（生产方渲染好的完整话术；消费侧直接呈现，缺省时消费侧按其余字段拼） */
    private String alertText;
}
