package com.betterschool.marketing.usecase.inventory;

import com.betterschool.marketing.domain.inventory.service.IInventoryAllocationService;
import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 配额分配用例（award-inventory 两层模型的运营入口，case 层编排）：把投放池的 surplus
 * 分配给奖品位（1 奖品位 1 库存账户），抽奖此后只扣奖品库存账户、不碰池。
 * <p>
 * 编排（顺序调用，无跨域事务，CLAUDE.md §1）：
 * <ol>
 *   <li>抽奖域校验奖品位存在（跨域只读经消费方仓储——分配入口在 case 层，直接读两域）；</li>
 *   <li>库存域分配：校验池 open → 一个事务（池守卫转出 + 奖品库存转入 + 奖品侧
 *       {@code AWARD_ALLOCATE} 明细 + 池侧分配流水），返回即已提交。</li>
 * </ol>
 * 幂等：分配单号是两侧流水的唯一锚——调用方重试**必须带同一个 allocBizId**（缺省时本用例生成一个，
 * 但那样每次重试都是新单，重放幂等只对同单号成立）；撞单号 = 本单已执行，按成功回放。
 * <p>
 * 池余量不足返回 false（业务事实，调用方提示投放不足）；池不存在 / 非 open / 奖品位不存在抛参数错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaAllocateCase {

    private final IInventoryAllocationService inventoryAllocationService;
    private final IRaffleRepository raffleRepository;

    /**
     * 分配 quantity 份池余量给奖品位。
     *
     * @param poolSegment 池段（{@code InventorySegmentVO} 的 code：quota / virtual），账户类型由它唯一决定
     * @param poolInvId   池台账业务 ID（转出来源）
     * @param awardId     目标奖品位 id（奖品库存账户维度）
     * @param quantity    本次分配份数（&gt; 0）
     * @param allocBizId  分配单号（幂等锚点；blank = 本用例生成，但跨次重试不复用）
     * @return true = 已转移（或本单已执行过，按成功回放）；false = 池余量不足
     */
    public boolean allocate(String poolSegment, String poolInvId, String awardId, long quantity, String allocBizId) {
        if (poolSegment == null || poolSegment.isBlank() || poolInvId == null || poolInvId.isBlank()
                || awardId == null || awardId.isBlank() || quantity <= 0) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(), "分配参数不完整");
        }
        InventorySegmentVO segment = InventorySegmentVO.fromCode(poolSegment);
        if (segment == null) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(),
                    "未知池段 segment=" + poolSegment);
        }
        if (!raffleRepository.existsAward(awardId)) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(),
                    "奖品位不存在 awardId=" + awardId);
        }
        String orderId = allocBizId == null || allocBizId.isBlank()
                ? UUID.randomUUID().toString() : allocBizId;
        boolean allocated = inventoryAllocationService.allocate(segment, poolInvId, awardId, quantity, orderId);
        log.info("[QuotaAllocate] 分配用例完成 segment={} poolInvId={} awardId={} quantity={} allocBizId={} result={}",
                poolSegment, poolInvId, awardId, quantity, orderId, allocated ? "已转移" : "池余量不足");
        return allocated;
    }
}
