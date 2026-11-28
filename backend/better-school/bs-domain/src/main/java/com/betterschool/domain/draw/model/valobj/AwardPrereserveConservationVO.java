package com.betterschool.domain.draw.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * B3 预扣守恒的对账读数（按 {@code award_id} 一行，期望/实际两侧同一条查询返回，差异判断在域侧）：
 * {@code waitingQty + issuedQty == deductedQty}。
 * 公式出处：{@code AwardInventoryDetailMapper.xml} 删预扣明细语句上方注释的守恒判据 B3
 * （「台账转回而不删行即破等式」）与 {@code marketing/docs/db/award.md} §0；
 * 回补是「台账转回 + 删明细」同事务，公式天然平衡。
 * VIRTUAL 行 waiting 恒 0，此时等式即 {@code issued == Σ预扣明细}（§0 虚拟行口径）。
 */
@Data
@Builder
public class AwardPrereserveConservationVO {

    /** 奖品位 id（奖品库存账户维度，1 奖品位 1 账户） */
    private String awardId;
    /** 台账 {@code waiting_qty}（VIRTUAL 恒 0） */
    private Long waitingQty;
    /** 台账 {@code issued_qty} */
    private Long issuedQty;
    /** 期望侧：Σ(flow_type = 1 预扣明细 quantity)，数据窗口内 */
    private Long deductedQty;
}
