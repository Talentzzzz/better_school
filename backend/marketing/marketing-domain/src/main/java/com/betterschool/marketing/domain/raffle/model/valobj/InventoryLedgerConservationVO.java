package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * B1 台账守恒的对账读数（按 {@code award_id} 一行，期望/实际两侧同一条查询返回，差异判断在域侧）：
 * <ul>
 *   <li>限量户（{@code surplusQty != -1}）：{@code surplus + waiting + issued == allocatedQty}</li>
 *   <li>不限量户（{@code surplusQty == -1}）：跳过 surplus 项，{@code waiting + issued == allocatedQty}</li>
 * </ul>
 * 公式出处：库存 flush 落库已声明的守恒判据与 {@code marketing/docs/db/inventory.md} §0
 * （三列守恒 + 分配行只在 surplus 与池之间搬运的口径）。
 * VIRTUAL 行 {@code waitingQty} 恒 0，公式自然成立，无需特判。
 */
@Data
@Builder
public class InventoryLedgerConservationVO {

    /** 奖品位 id（奖品库存账户维度，1 奖品位 1 账户） */
    private String awardId;
    /** 台账 {@code surplus_qty}（-1 = 不限量：守恒比较跳过本列） */
    private Long surplusQty;
    /** 台账 {@code waiting_qty}（VIRTUAL 恒 0） */
    private Long waitingQty;
    /** 台账 {@code issued_qty} */
    private Long issuedQty;
    /** 期望侧：Σ(flow_type = 0 分配转入明细 quantity)，数据窗口内 */
    private Long allocatedQty;
}
