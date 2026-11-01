package com.betterschool.marketing.domain.inventory.model.valobj;

/**
 * 库存台账行的公共形状：两段 ledger 表（{@code virtual_inventory_quota} /
 * {@code physical_marketing_quota}）的差异字段（虚拟的 {@code type} / {@code value}、实物的 {@code decr}）
 * 都不进这里——域服务只关心「在不在、开不开、账上多少」这三件事。
 * <p>
 * {@code waitingQty} 在虚拟段恒 0（虚拟没有兑换确认，合成就直接进 {@code issued_qty}）；
 * 实物段对账口径 {@code waiting + issued} 因此在两段是同一个表达（spec §8）。
 */
public record InventoryLedgerVO(Long invId,
                                Long surplusQty,
                                Long waitingQty,
                                Long issuedQty,
                                String state) {

    /** 台账是否可预扣：只有 {@code open} 能扣（{@code exhausted} / {@code closed} 都拒绝） */
    public boolean isOpen() {
        return "open".equals(state);
    }
}
