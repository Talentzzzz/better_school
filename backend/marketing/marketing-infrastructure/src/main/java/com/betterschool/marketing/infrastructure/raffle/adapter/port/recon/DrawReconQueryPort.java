package com.betterschool.marketing.infrastructure.raffle.adapter.port.recon;

import com.betterschool.marketing.domain.raffle.adapter.port.recon.IDrawReconQueryPort;
import com.betterschool.marketing.domain.raffle.model.valobj.InventoryLedgerConservationVO;
import com.betterschool.marketing.domain.raffle.model.valobj.InventoryPrereserveConservationVO;
import com.betterschool.marketing.domain.raffle.model.valobj.RecordMissingDeductVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UnmatchedDeductDetailVO;
import com.betterschool.marketing.infrastructure.inventory.dao.IInventoryReconQueryDao;
import com.betterschool.marketing.infrastructure.inventory.dao.po.InventoryLedgerConservationPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.InventoryPrereserveConservationPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.UnmatchedDeductDetailPO;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawRecordReconQueryDao;
import com.betterschool.marketing.infrastructure.raffle.dao.po.RecordMissingDeductPO;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账轮只读查询端口的实现：纯透传委托两条聚合 DAO，在这里填库存侧口径
 * （{@code biz_type = LOTTERY_DRAW}，抽奖域不认识库存的表列），PO 翻成对账轮自己的值对象。
 */
@Component
@RequiredArgsConstructor
public class DrawReconQueryPort implements IDrawReconQueryPort {

    private final IInventoryReconQueryDao inventoryReconQueryDao;
    private final IDrawRecordReconQueryDao drawRecordReconQueryDao;

    @Override
    public List<InventoryLedgerConservationVO> queryLedgerConservation(LocalDateTime before) {
        return inventoryReconQueryDao.queryLedgerConservation(before).stream().map(this::toLedgerConservation).toList();
    }

    @Override
    public List<InventoryPrereserveConservationVO> queryPrereserveConservation(LocalDateTime before) {
        return inventoryReconQueryDao.queryPrereserveConservation(before).stream()
                .map(this::toPrereserveConservation).toList();
    }

    @Override
    public List<UnmatchedDeductDetailVO> listUnmatchedDeductDetails(LocalDateTime before) {
        return inventoryReconQueryDao
                .listUnmatchedDeductDetails(before, InventoryBizTypes.LOTTERY_DRAW).stream()
                .map(this::toUnmatchedDeductDetail).toList();
    }

    @Override
    public List<RecordMissingDeductVO> listRecordsMissingDeduct(LocalDateTime before) {
        return drawRecordReconQueryDao
                .listRecordsMissingDeduct(before, InventoryBizTypes.LOTTERY_DRAW).stream()
                .map(this::toRecordMissingDeduct).toList();
    }

    private InventoryLedgerConservationVO toLedgerConservation(InventoryLedgerConservationPO conservationPO) {
        return InventoryLedgerConservationVO.builder()
                .awardId(conservationPO.getAwardId())
                .surplusQty(conservationPO.getSurplusQty())
                .waitingQty(conservationPO.getWaitingQty())
                .issuedQty(conservationPO.getIssuedQty())
                .allocatedQty(conservationPO.getAllocatedQty())
                .build();
    }

    private InventoryPrereserveConservationVO toPrereserveConservation(InventoryPrereserveConservationPO conservationPO) {
        return InventoryPrereserveConservationVO.builder()
                .awardId(conservationPO.getAwardId())
                .waitingQty(conservationPO.getWaitingQty())
                .issuedQty(conservationPO.getIssuedQty())
                .deductedQty(conservationPO.getDeductedQty())
                .build();
    }

    private UnmatchedDeductDetailVO toUnmatchedDeductDetail(UnmatchedDeductDetailPO detailPO) {
        return UnmatchedDeductDetailVO.builder()
                .awardId(detailPO.getAwardId())
                .bizId(detailPO.getBizId())
                .quantity(detailPO.getQuantity())
                .build();
    }

    private RecordMissingDeductVO toRecordMissingDeduct(RecordMissingDeductPO deductPO) {
        return RecordMissingDeductVO.builder()
                .recordId(deductPO.getRecordId())
                .invId(deductPO.getInvId())
                .userId(deductPO.getUserId())
                .build();
    }
}
