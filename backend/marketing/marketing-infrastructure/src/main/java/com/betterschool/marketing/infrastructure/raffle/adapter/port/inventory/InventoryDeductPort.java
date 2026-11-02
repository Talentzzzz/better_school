package com.betterschool.marketing.infrastructure.raffle.adapter.port.inventory;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.domain.raffle.adapter.port.inventory.IInventoryDeductPort;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 库存域发放预扣端口的实现：委托唯一的库存配额服务（段由本实现选），
 * 结果对象翻成端口自己的 {@link Outcome}（不把库存域的值对象类型漏进抽奖域）。
 * <p>
 * 纯透传：{@code recordId} 原样交给库存域服务，并在这里填 {@code bizType} 与段
 * （抽奖域不认识库存的表列 / 分段）。
 */
@Component
@RequiredArgsConstructor
public class InventoryDeductPort implements IInventoryDeductPort {

    private final IInventoryQuotaService inventoryQuotaService;

    @Override
    public boolean preDeductVirtual(String invId, long quantity, String recordId) {
        return inventoryQuotaService.preDeduct(InventorySegmentVO.VIRTUAL, invId, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
    }

    @Override
    public Outcome preDeductPhysicalWithFallback(String invId, String fallbackInvId, long quantity, String recordId) {
        InventoryDeductRequestVO primary = new InventoryDeductRequestVO(InventorySegmentVO.QUOTA, invId, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
        InventoryDeductRequestVO fallback = fallbackInvId == null ? null
                : new InventoryDeductRequestVO(InventorySegmentVO.VIRTUAL, fallbackInvId, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
        DeductOutcomeVO outcome = inventoryQuotaService.preDeductWithFallback(primary, fallback);
        return switch (outcome) {
            case DEDUCTED -> Outcome.DEDUCTED;
            case FALLBACK -> Outcome.FALLBACK;
            case FAILED -> Outcome.FAILED;
        };
    }
}
