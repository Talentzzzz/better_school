package com.betterschool.infrastructure.adapter.port.raffle;

import com.betterschool.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.domain.raffle.adapter.port.inventory.IQuotaConfirmPort;
import com.betterschool.types.inventory.InventoryBizTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 兑换确认端口的实现：委托库存配额服务的确认动作（口径注释见域端口）。
 * {@code bizType} 由本实现填——抽奖域只透传 {@code recordId}，不认识库存的表列。
 */
@Component
@RequiredArgsConstructor
public class QuotaConfirmPort implements IQuotaConfirmPort {

    private final IInventoryQuotaService inventoryQuotaService;

    @Override
    public void confirmIssued(String invId, String recordId, long quantity) {
        inventoryQuotaService.confirmIssued(invId, InventoryBizTypes.LOTTERY_CLAIM, recordId, quantity);
    }
}
