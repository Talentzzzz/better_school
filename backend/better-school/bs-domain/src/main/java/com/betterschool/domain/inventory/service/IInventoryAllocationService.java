package com.betterschool.domain.inventory.service;

import com.betterschool.types.inventory.InventorySegmentVO;

/**
 * 分配域服务（award-inventory 两层模型的「分配即转移」入口）：把配额池的 {@code surplus}
 * 转移给奖品库存账户，池从此不再被抽奖直接扣减。
 * <p>
 * 校验（池 open）在本服务，落库（一个事务：池守卫转出 + 奖品库存转入 + 奖品侧
 * {@code AWARD_ALLOCATE} 明细 + 池侧分配流水）在仓储 {@code IInventoryRepository#allocate}——
 * 域服务只调一次仓储方法，事务落位在仓储内（CLAUDE.md §1）。分配是低频运营操作：
 * 不进合并器，同步一事务，返回即已提交。
 * <p>
 * 幂等：两侧流水唯一键（奖品侧 {@code uk_award_biz}、池侧 {@code uk_alloc_biz}）都以分配单号为锚，
 * 撞单号 = 本单已执行，按成功回放。
 * <p>
 * 奖品位存在性校验不在本服务：库存域不认识抽奖域的表。调用方（管理/投放侧 usecase，case 层）
 * 先经抽奖域确认奖品位存在，再调本方法。
 */
public interface IInventoryAllocationService {

    /**
     * 分配 quantity 份：池 surplus 守卫转出 → 奖品库存账户 surplus 转入。
     *
     * @param poolSegment 池段（QUOTA = 实物营销配额，VIRTUAL = 虚拟库存配额）；账户类型由池段唯一决定
     * @param poolInvId   池台账业务 ID（转出来源）
     * @param awardId     奖品位 id（奖品库存账户维度；账户不存在则按池段建户）
     * @param quantity    本次分配份数（&gt; 0）
     * @param allocBizId  分配单号（两侧流水的幂等锚点）
     * @return true = 已转移（或本单已执行过，按成功回放）；false = 池余量不足（业务事实）
     * @throws com.betterschool.types.common.AppException 池不存在（参数错）或池非 open（closed /
     *              exhausted 拒绝分配），或奖品库存账户缺失（不变量破坏）
     */
    boolean allocate(InventorySegmentVO poolSegment, String poolInvId, String awardId,
                     long quantity, String allocBizId);
}
