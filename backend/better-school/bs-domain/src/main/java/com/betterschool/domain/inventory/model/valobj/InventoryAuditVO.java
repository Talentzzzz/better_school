package com.betterschool.domain.inventory.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * 对账读数（票 08 起瘦成台账三列 + invId 的直读）：台账就是唯一真相，
 * 不再有「Redis 余量面」「未合并 / 已合并」这些推导视图——守恒等式降级为集成测试断言
 * （{@code InventoryQuotaConservationIntegrationTest} 的 B2 / B3），不留在运行时读数里。
 */
@Data
@Builder
public class InventoryAuditVO {

    private String invId;
    /** 台账 {@code surplus_qty}（{@code -1 = 不限量}） */
    private Long surplusQty;
    /** 台账 {@code waiting_qty}（虚拟恒 0） */
    private Long ledgerWaitingQty;
    /** 台账 {@code issued_qty} */
    private Long ledgerIssuedQty;
}
