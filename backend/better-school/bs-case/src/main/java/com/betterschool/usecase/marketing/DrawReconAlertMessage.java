package com.betterschool.usecase.marketing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 营销库存对账告警事件（topic {@code bs-marketing-recon-alert}）的单体侧副本：
 * 生产方 = 营销对账轮（drawReconScan，发现 CONFIRMED 差错 / DEAD 孤儿单时点名），
 * 消费方 = 本体（落 BOTTOM_SYSTEM 维护者告警通知）。跨服务 wire 层有意各持一份同字段副本
 * （先例见 {@link LotteryDrawnMessage}）；落库幂等 = 通知 dedup_key
 * {@code MARKETING:RECON:<roundNo>:<checkType>:<bizId>}，重复点名只落一条。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DrawReconAlertMessage {

    /** 对账轮次号（差错表 round_no） */
    private String roundNo;
    /** 检查类型（差错表 check_type） */
    private String checkType;
    /** 定位键 scope_key（award_id 或 record_id；无自然定位键的检查为空） */
    private String scopeKey;
    /** 定位单号 biz_id（= 差错表 biz_id） */
    private String bizId;
    /** 期望值快照摘要（公式左侧） */
    private String expectSummary;
    /** 实际值快照摘要（公式右侧实读数） */
    private String actualSummary;
    /** 告警文案（生产方渲染好的完整话术；缺省时消费侧按其余字段拼） */
    private String alertText;
}
