package com.betterschool.marketing.domain.inventory.service;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryAuditVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 库存对账实现（票 08 起只做台账直读）：预扣与确认由进程内合并器一组一事务直接落账，
 * 台账三列就是唯一真相，读数不需要任何推导——守恒等式由集成测试断言（B2 / B3）锁住，
 * 生产上账不平的信号只剩死信队列、{@code draw_record} 停在待发货、人工查台账三条路
 * （spec §9，本工程无 metrics 端点、不建对账 job）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAuditServiceImpl implements IInventoryAuditService {

    private final IInventoryRepository inventoryRepository;

    @Override
    public InventoryAuditVO audit(InventorySegmentVO segment, Long invId) {
        InventoryLedgerVO ledger = inventoryRepository.queryLedger(segment, invId);
        if (ledger == null) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(),
                    "库存不存在 segment=" + segment.getCode() + " invId=" + invId);
        }
        return InventoryAuditVO.builder()
                .invId(invId)
                .surplusQty(ledger.surplusQty())
                .ledgerWaitingQty(ledger.waitingQty())
                .ledgerIssuedQty(ledger.issuedQty())
                .build();
    }
}
