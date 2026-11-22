package com.betterschool.marketing.infrastructure.inventory.adapter.repository;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.domain.inventory.model.valobj.OrphanPrereserveCandidateVO;
import com.betterschool.marketing.infrastructure.inventory.dao.IAwardInventoryDao;
import com.betterschool.marketing.infrastructure.inventory.dao.IAwardInventoryDetailDao;
import com.betterschool.marketing.infrastructure.inventory.dao.IPhysicalQuotaDao;
import com.betterschool.marketing.infrastructure.inventory.dao.IQuotaAllocateFlowDao;
import com.betterschool.marketing.infrastructure.inventory.dao.IVirtualInventoryQuotaDao;
import com.betterschool.marketing.infrastructure.inventory.dao.po.AwardInventoryDetailPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.AwardInventoryPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.PhysicalMarketingQuotaPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.QuotaAllocateFlowPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.VirtualInventoryQuotaPO;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
import com.betterschool.marketing.types.inventory.Constants;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存域唯一仓储实现（门面），award-inventory 两层模型：
 * <ul>
 *   <li><b>池层</b>：查询走两张池台账 DAO（{@code switch} 按 {@link InventorySegmentVO} 分派）——
 *       池只剩查询与分配转出，预扣类 SQL 已从池 DAO 退役；</li>
 *   <li><b>奖品库存层</b>：统一台账/明细 DAO（{@code IAwardInventoryDao} / {@code IAwardInventoryDetailDao}），
 *       实物/虚拟一张表，flush 落库按 {@link AwardInventoryType} 在 SQL 层分支。</li>
 * </ul>
 * <p>
 * 预扣 / 确认的写入口只呈现「一件」语义（{@link #deduct} / {@link #claim}）：
 * 一件是并进一批落库还是单件落库、合并器忙时怎么降级，全部交给注入的合并写入组件
 * {@link InventoryQuotaMergeWriter}——它内部是「入队 → 等窗口 → 一组一事务落库 → 降级」，
 * 落库 SQL 在 {@link InventoryQuotaFlushService}。域因此不认识合并器：域只调本类的一件方法，
 * 拿一个业务结论。
 * <p>
 * 分配（{@link #allocate}）：低频运营操作，不进合并——本类用 {@code TransactionTemplate}（REQUIRED）
 * 收一个事务：池 surplus 守卫转出 + 奖品库存 surplus 转入 + 奖品侧 {@code AWARD_ALLOCATE} 明细 +
 * 池侧分配流水（独立流水表），双侧流水唯一键都以分配单号为锚，撞单号 = 本单已执行，按成功回放。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class InventoryRepository implements IInventoryRepository {

    private final IVirtualInventoryQuotaDao virtualInventoryQuotaDao;
    private final IPhysicalQuotaDao physicalQuotaDao;
    private final IAwardInventoryDao awardInventoryDao;
    private final IAwardInventoryDetailDao awardInventoryDetailDao;
    private final IQuotaAllocateFlowDao quotaAllocateFlowDao;
    private final InventoryQuotaFlushService flushService;
    /** 合并写入组件：仓储要用合并就注入它，域看不到这个依赖 */
    private final InventoryQuotaMergeWriter mergeWriter;
    private final TransactionTemplate transactionTemplate;

    // ---------------------------------------------------------------- 池层：台账查询

    @Override
    public InventoryLedgerVO queryLedger(InventorySegmentVO segment, String invId) {
        return switch (segment) {
            case VIRTUAL -> toVirtualLedger(virtualInventoryQuotaDao.selectInventory(invId));
            case QUOTA -> toQuotaLedger(physicalQuotaDao.selectQuota(invId));
        };
    }

    @Override
    public List<InventoryLedgerVO> listOpenLedgers(InventorySegmentVO segment) {
        return switch (segment) {
            case VIRTUAL -> virtualInventoryQuotaDao.selectOpenInventories().stream().map(this::toVirtualLedger).toList();
            case QUOTA -> physicalQuotaDao.selectOpenQuotas().stream().map(this::toQuotaLedger).toList();
        };
    }

    // ---------------------------------------------------------------- 分配（池 → 奖品库存，一个事务）

    /**
     * 分配（分配即转移，spec §0 决策 1），一个事务：
     * <pre>
     * BEGIN
     *   INSERT IGNORE 奖品侧 AWARD_ALLOCATE 明细（award_id, flow_type=0, biz_id=分配单号）  -- 幂等裁决先行
     *   INSERT IGNORE 池侧分配流水（alloc_biz_id=分配单号）                                -- 双侧流水，同锚
     *   两边任一撞键 = 本单已执行 → 提交空事务，按成功回放
     *   UPDATE 池   SET surplus -= N  WHERE inv_id=? AND state='open' AND (surplus &lt; 0 OR surplus &gt;= N)
     *   UPDATE 台账 SET surplus += N  WHERE award_id=?（无户建户，upsert）
     * COMMIT
     * </pre>
     * 流水先行：撞分配单号在动池之前就短路，重放零副作用；池守卫不过（余量不足 / 池被并发改动）
     * 则整体回滚（含流水），返回 false。奖品账户缺失不可能发生——{@code allocateIn} 是 upsert
     * （无户按池段建户）；账户行 upsert 0 行只能是约束级异常，抛出让事务回滚。
     */
    @Override
    public boolean allocate(InventorySegmentVO poolSegment, String poolInvId, String awardId,
                            long quantity, String allocBizId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 幂等裁决先行：奖品侧明细 + 池侧流水都以分配单号为锚，撞单号 = 本单已执行，按成功回放
            int awardRows = awardInventoryDetailDao.insertIgnoreAllocateDetail(
                    toAllocateDetailPO(awardId, quantity, allocBizId));
            int poolRows = quotaAllocateFlowDao.insertIgnoreAllocateFlow(
                    toAllocateFlowPO(poolSegment, poolInvId, awardId, quantity, allocBizId));
            if (awardRows == 0 || poolRows == 0) {
                return true;
            }
            // 池守卫转出：0 行 = 余量不足（业务事实）；-1 = 不限量放行、不减 surplus
            int deducted = switch (poolSegment) {
                case VIRTUAL -> virtualInventoryQuotaDao.deductForAllocate(poolInvId, quantity);
                case QUOTA -> physicalQuotaDao.deductForAllocate(poolInvId, quantity);
            };
            if (deducted == 0) {
                status.setRollbackOnly();
                return false;
            }
            // 奖品库存转入：账户类型由池段唯一决定；虚拟池把单次面额一并带给账户（value_qty）
            int allocated = awardInventoryDao.allocateIn(awardId, accountTypeOf(poolSegment).name(),
                    poolValueOf(poolSegment, poolInvId), quantity);
            if (allocated == 0) {
                log.error("分配转入奖品库存 0 行（不变量破坏，整组回滚） poolSegment={} poolInvId={} awardId={}",
                        poolSegment.getCode(), poolInvId, awardId);
                throw new IllegalStateException("分配转入奖品库存失败（不变量破坏，整组回滚） awardId=" + awardId);
            }
            return true;
        }));
    }

    /** 账户类型由池段唯一决定：实物营销配额池 → PHYSICAL，虚拟库存配额池 → VIRTUAL */
    private AwardInventoryType accountTypeOf(InventorySegmentVO poolSegment) {
        return switch (poolSegment) {
            case QUOTA -> AwardInventoryType.PHYSICAL;
            case VIRTUAL -> AwardInventoryType.VIRTUAL;
        };
    }

    /** 虚拟池的单次发放面额（value 列）随分配带给账户（value_qty）；实物池没有面额，落 null */
    private Long poolValueOf(InventorySegmentVO poolSegment, String poolInvId) {
        if (poolSegment != InventorySegmentVO.VIRTUAL) {
            return null;
        }
        VirtualInventoryQuotaPO pool = virtualInventoryQuotaDao.selectInventory(poolInvId);
        return pool == null ? null : pool.getValue() == null ? null : pool.getValue().longValue();
    }

    private AwardInventoryDetailPO toAllocateDetailPO(String awardId, long quantity, String allocBizId) {
        AwardInventoryDetailPO detailPO = new AwardInventoryDetailPO();
        detailPO.setAwardId(awardId);
        detailPO.setFlowType(Constants.FLOW_TYPE_ALLOCATE);
        detailPO.setQuantity(quantity);
        detailPO.setBizType(InventoryBizTypes.AWARD_ALLOCATE);
        detailPO.setBizId(allocBizId);
        return detailPO;
    }

    private QuotaAllocateFlowPO toAllocateFlowPO(InventorySegmentVO poolSegment, String poolInvId,
                                                 String awardId, long quantity, String allocBizId) {
        QuotaAllocateFlowPO flowPO = new QuotaAllocateFlowPO();
        flowPO.setPoolSegment(poolSegment.getCode());
        flowPO.setPoolInvId(poolInvId);
        flowPO.setAwardId(awardId);
        flowPO.setQuantity(quantity);
        flowPO.setAllocBizId(allocBizId);
        return flowPO;
    }

    // ---------------------------------------------------------------- 奖品库存层：台账查询

    @Override
    public InventoryLedgerVO queryAwardInventory(String awardId) {
        return toAwardLedger(awardInventoryDao.selectByAwardId(awardId));
    }

    @Override
    public List<InventoryLedgerVO> listOpenAwardInventories() {
        return awardInventoryDao.selectOpenAwardInventories().stream().map(this::toAwardLedger).toList();
    }

    // ---------------------------------------------------------------- 预扣 / 确认（交合并写入组件）

    /**
     * 预扣一件：交给 {@link InventoryQuotaMergeWriter}（入队等一批 / 缓冲满或停机时同步落一件 / 等不到回可重试）。
     * 本类只做转发——「要不要合并」是基础设施的装配选择，不是本方法契约的一部分。
     */
    @Override
    public DeductResult deduct(InventoryDeductRequestVO request) {
        return mergeWriter.deduct(request);
    }

    @Override
    public boolean existsDeductDetail(String awardId, String bizType, String bizId) {
        return awardInventoryDetailDao.existsDeductDetail(awardId, bizType, bizId);
    }

    /** 兑换确认一件：同 {@link #deduct}，走同一个合并组件（同分片，撞片无害且是顺序的前提） */
    @Override
    public ClaimOutcome claim(InventoryClaimRequestVO request) {
        return mergeWriter.claim(request);
    }

    // ---------------------------------------------------------------- 孤儿预扣对账（低频直落，不进合并器）

    @Override
    public List<OrphanPrereserveCandidateVO> listOrphanPrereserveDetails(LocalDateTime before, int limit) {
        return awardInventoryDetailDao.listOrphanDrawPrereserveDetails(before, limit).stream()
                .map(po -> new OrphanPrereserveCandidateVO(po.getAwardId(), po.getBizId(), po.getQuantity()))
                .toList();
    }

    /** 委托 {@link InventoryQuotaFlushService#restitutePrereserve}：回补与预扣/确认共用同一落库组件 */
    @Override
    public boolean restitutePrereserve(String awardId, String bizType, String bizId, long quantity) {
        return flushService.restitutePrereserve(awardId, bizType, bizId, quantity);
    }

    // ---------------------------------------------------------------- PO → 公共形状

    /** 虚拟池没有 waiting 列：显式补齐 0，让两层共用 {@code waiting + issued} 的对账表达 */
    private InventoryLedgerVO toVirtualLedger(VirtualInventoryQuotaPO po) {
        if (po == null) {
            return null;
        }
        return new InventoryLedgerVO(po.getInvId(), po.getSurplusQty(), 0L, po.getIssuedQty(), po.getState());
    }

    private InventoryLedgerVO toQuotaLedger(PhysicalMarketingQuotaPO po) {
        if (po == null) {
            return null;
        }
        return new InventoryLedgerVO(po.getInvId(), po.getSurplusQty(), po.getWaitingQty(),
                po.getIssuedQty(), po.getState());
    }

    /** 统一台账的 state 落 TINYINT（0 open / 1 exhausted / 2 closed）：翻成 VO 的字符串口径 */
    private InventoryLedgerVO toAwardLedger(AwardInventoryPO po) {
        if (po == null) {
            return null;
        }
        return new InventoryLedgerVO(po.getAwardId(), po.getSurplusQty(), po.getWaitingQty(),
                po.getIssuedQty(), awardStateOf(po.getState()));
    }

    private String awardStateOf(Integer state) {
        if (state == null) {
            return "open";
        }
        return switch (state) {
            case 0 -> "open";
            case 1 -> "exhausted";
            case 2 -> "closed";
            default -> "open";
        };
    }
}
