package com.betterschool.marketing.domain.inventory.service;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 分配域服务实现（award-inventory 两层模型的「分配即转移」）：只做池侧校验（存在 + open），
 * 落库交给仓储的同步一事务——池 surplus 守卫转出 + 奖品库存账户 surplus 转入 + 两侧流水
 * （奖品侧 {@code AWARD_ALLOCATE} 明细行 + 池侧分配流水行），返回即已提交。
 * <p>
 * 低频运营操作：不进合并器（合并器是抽奖热路径的攒批引擎，分配没有合并收益）。
 * 池非 open（closed / exhausted）拒绝分配——投放停了就不再给奖品位补货；exhausted 池的复活
 * 属于投放操作（改池 surplus），不在分配路径里隐式做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAllocationServiceImpl implements IInventoryAllocationService {

    private final IInventoryRepository inventoryRepository;

    @Override
    public boolean allocate(InventorySegmentVO poolSegment, String poolInvId, String awardId,
                            long quantity, String allocBizId) {
        InventoryLedgerVO pool = inventoryRepository.queryLedger(poolSegment, poolInvId);
        if (pool == null) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(),
                    "配额池不存在 segment=" + poolSegment.getCode() + " invId=" + poolInvId);
        }
        if (!pool.isOpen()) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(),
                    "配额池非 open，拒绝分配 segment=" + poolSegment.getCode()
                            + " invId=" + poolInvId + " state=" + pool.state());
        }
        boolean allocated = inventoryRepository.allocate(poolSegment, poolInvId, awardId, quantity, allocBizId);
        if (allocated) {
            log.info("配额池分配已提交 segment={} invId={} awardId={} quantity={} allocBizId={}",
                    poolSegment.getCode(), poolInvId, awardId, quantity, allocBizId);
        } else {
            log.info("配额池分配余量不足 segment={} invId={} awardId={} quantity={} allocBizId={}",
                    poolSegment.getCode(), poolInvId, awardId, quantity, allocBizId);
        }
        return allocated;
    }
}
