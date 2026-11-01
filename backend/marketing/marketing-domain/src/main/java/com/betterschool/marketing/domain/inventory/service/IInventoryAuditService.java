package com.betterschool.marketing.domain.inventory.service;

import com.betterschool.marketing.domain.inventory.model.valobj.InventoryAuditVO;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;

/**
 * 库存对账：**可重复执行的检查口径**（票 12 验收 4；票 08 起只做台账直读）。
 * <p>
 * 台账三列（{@code surplus / waiting / issued}）是唯一真相：预扣与确认由进程内合并器
 * 一组一事务直接落账，没有「Redis 余量面」也没有「未合并 / 已合并」的推导视图。
 * 守恒等式由集成测试断言锁住（{@code InventoryQuotaConservationIntegrationTest} 的 B2 / B3），
 * 不留在运行时读数里。报告只暴露读数，**不修数据**。
 */
public interface IInventoryAuditService {

    /**
     * 台账直读：段只决定仓储分派。
     *
     * @param segment 段（{@link InventorySegmentVO#QUOTA} / {@link InventorySegmentVO#VIRTUAL}）
     * @param invId   库存业务 id
     * @return 台账三列读数（{@code invId / surplusQty / ledgerWaitingQty / ledgerIssuedQty}）
     * @throws com.betterschool.marketing.types.common.AppException 该段没有这个 inv 的台账
     */
    InventoryAuditVO audit(InventorySegmentVO segment, Long invId);
}
