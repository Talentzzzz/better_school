package com.betterschool.infrastructure.adapter.repository.award;

import com.betterschool.domain.award.adapter.repository.IAwardRepository;
import com.betterschool.domain.award.model.valobj.AwardClaimRequestVO;
import com.betterschool.domain.award.model.valobj.AwardDeductRequestVO;
import com.betterschool.domain.award.model.valobj.AwardLedgerVO;
import com.betterschool.domain.award.model.valobj.ClaimOutcomeEnum;
import com.betterschool.domain.award.model.valobj.DeductResultEnum;
import com.betterschool.domain.award.model.valobj.OrphanPrereserveCandidateVO;
import com.betterschool.infrastructure.dao.award.IAwardInventoryDao;
import com.betterschool.infrastructure.dao.award.IAwardInventoryDetailDao;
import com.betterschool.infrastructure.dao.award.po.AwardInventoryPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 奖品域唯一仓储实现（门面）：台账 / 明细 DAO（{@code IAwardInventoryDao} /
 * {@code IAwardInventoryDetailDao}），实物 / 虚拟一张表，flush 落库按
 * {@link com.betterschool.types.award.AwardInventoryTypeEnum} 在 SQL 层分支。
 * <p>
 * 预扣 / 确认的写入口只呈现「一件」语义（{@link #deduct} / {@link #claim}）：
 * 一件是并进一批落库还是单件落库、合并器忙时怎么降级，全部交给注入的合并写入组件
 * {@link AwardInventoryMergeWriter}——它内部是「入队 → 等窗口 → 一组一事务落库 → 降级」，
 * 落库 SQL 在 {@link AwardInventoryFlushService}。域因此不认识合并器：域只调本类的一件方法，
 * 拿一个业务结论。
 * <p>
 * 库存投放入口（{@link #upsertInventory}）：低频运营操作，直接写台账（无池、无分配链路），
 * 一条 upsert 即完成。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AwardRepository implements IAwardRepository {

    private final IAwardInventoryDao awardInventoryDao;
    private final IAwardInventoryDetailDao awardInventoryDetailDao;
    private final AwardInventoryFlushService flushService;
    /** 合并写入组件：仓储要用合并就注入它，域看不到这个依赖 */
    private final AwardInventoryMergeWriter mergeWriter;

    // ---------------------------------------------------------------- 台账维护（运营直接投放 / 改量）

    @Override
    public boolean upsertInventory(String awardId, String type, Long surplusQty, Long valueQty, Integer state) {
        if (awardId == null || awardId.isBlank() || type == null) {
            return false;
        }
        awardInventoryDao.upsertInventory(awardId, type, surplusQty, valueQty, state);
        log.info("奖品库存已维护 awardId={} type={} surplusQty={} valueQty={} state={}",
                awardId, type, surplusQty, valueQty, state);
        return true;
    }

    // ---------------------------------------------------------------- 奖品库存层：台账查询

    @Override
    public AwardLedgerVO queryAwardInventory(String awardId) {
        return toAwardLedger(awardInventoryDao.selectByAwardId(awardId));
    }

    @Override
    public List<AwardLedgerVO> listOpenAwardInventories() {
        return awardInventoryDao.selectOpenAwardInventories().stream().map(this::toAwardLedger).toList();
    }

    // ---------------------------------------------------------------- 预扣 / 确认（交合并写入组件）

    /**
     * 预扣一件：交给 {@link AwardInventoryMergeWriter}（入队等一批 / 缓冲满或停机时同步落一件 / 等不到回可重试）。
     * 本类只做转发——「要不要合并」是基础设施的装配选择，不是本方法契约的一部分。
     */
    @Override
    public DeductResultEnum deduct(AwardDeductRequestVO awardDeductRequestVO) {
        return mergeWriter.deduct(awardDeductRequestVO);
    }

    @Override
    public boolean existsDeductDetail(String awardId, String bizType, String bizId) {
        return awardInventoryDetailDao.existsDeductDetail(awardId, bizType, bizId);
    }

    /** 兑换确认一件：同 {@link #deduct}，走同一个合并组件（同分片，撞片无害且是顺序的前提） */
    @Override
    public ClaimOutcomeEnum claim(AwardClaimRequestVO awardClaimRequestVO) {
        return mergeWriter.claim(awardClaimRequestVO);
    }

    // ---------------------------------------------------------------- 孤儿预扣对账（低频直落，不进合并器）

    @Override
    public List<OrphanPrereserveCandidateVO> listOrphanPrereserveDetails(LocalDateTime before, int limit) {
        return awardInventoryDetailDao.listOrphanDrawPrereserveDetails(before, limit).stream()
                .map(po -> new OrphanPrereserveCandidateVO(po.getAwardId(), po.getBizId(), po.getQuantity()))
                .toList();
    }

    /** 委托 {@link AwardInventoryFlushService#restitutePrereserve}：回补与预扣/确认共用同一落库组件 */
    @Override
    public boolean restitutePrereserve(String awardId, String bizType, String bizId, long quantity) {
        return flushService.restitutePrereserve(awardId, bizType, bizId, quantity);
    }

    // ---------------------------------------------------------------- PO → 公共形状

    /** 台账的 state 落 TINYINT（0 open / 1 exhausted / 2 closed）：翻成 VO 的字符串口径 */
    private AwardLedgerVO toAwardLedger(AwardInventoryPO awardInventoryPO) {
        if (awardInventoryPO == null) {
            return null;
        }
        return new AwardLedgerVO(awardInventoryPO.getAwardId(), awardInventoryPO.getSurplusQty(), awardInventoryPO.getWaitingQty(),
                awardInventoryPO.getIssuedQty(), awardStateOf(awardInventoryPO.getState()));
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
