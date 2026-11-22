package com.betterschool.marketing.types.raffle;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 对账检查类型：落 {@code draw_recon_variance.check_type}，也是对账轮检查清单的扩展位
 * （二期加池侧守恒、次数桶、积分桶时在此追加，不重写编排与差错状态机）。
 * <p>
 * 前四类是四条不变量（公式全部来自库存 flush 落库注释与 docs/db/inventory.md §0
 * 已声明的守恒判据），第五类是死信点名；取值与 {@code draw_recon_variance.check_type}
 * 列 COMMENT 一一对应。
 */
@Getter
@AllArgsConstructor
public enum DrawReconCheckType {

    /** B1 台账守恒：限量户 surplus + waiting + issued = Σ(flow_type=0 分配转入明细 quantity)，按 award_id 分组 */
    B1_CONSERVATION("B1 台账守恒"),

    /** B3 预扣守恒：waiting + issued = Σ(flow_type=1 预扣明细 quantity)，按 award_id 分组（回补是「台账转回 + 删明细」同事务，公式天然平衡） */
    B3_CONSERVATION("B3 预扣守恒"),

    /** 正向配对：每条预扣明细（flow_type=1 且 biz_type=LOTTERY_DRAW）的 biz_id 必须命中 draw_record 或孤儿对账单，否则为漏结 */
    FORWARD_PAIRING("正向配对"),

    /** 反向配对：每条中奖记录（inv_id 非空且 award_type ≠ THANKS）必须存在对应预扣明细，缺失即数据破坏级 */
    REVERSE_PAIRING("反向配对"),

    /** DEAD 孤儿单点名：重试耗尽进死信的对账单周期性再点名告警，防「当次 ERROR 之后被遗忘」 */
    DEAD_ORPHAN_PATROL("DEAD 孤儿单点名");

    private final String desc;
}
