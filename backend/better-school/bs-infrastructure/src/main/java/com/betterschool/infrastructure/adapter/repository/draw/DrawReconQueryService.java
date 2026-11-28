package com.betterschool.infrastructure.adapter.repository.draw;

import com.betterschool.domain.draw.model.valobj.AwardPrereserveConservationVO;
import com.betterschool.domain.draw.model.valobj.RecordMissingDeductVO;
import com.betterschool.domain.draw.model.valobj.UnmatchedDeductDetailVO;
import com.betterschool.domain.draw.service.recon.IDrawReconQueryService;
import com.betterschool.infrastructure.dao.award.IAwardReconQueryDao;
import com.betterschool.infrastructure.dao.award.po.AwardPrereserveConservationPO;
import com.betterschool.infrastructure.dao.award.po.UnmatchedDeductDetailPO;
import com.betterschool.infrastructure.dao.draw.IDrawRecordReconQueryDao;
import com.betterschool.infrastructure.dao.draw.po.RecordMissingDeductPO;
import com.betterschool.types.award.AwardBizTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖库存对账的只读查询实现：委托聚合 DAO，并在这里填库存侧口径
 * （{@code biz_type = LOTTERY_DRAW}），PO 翻成对账自己的值对象。
 * <p>
 * 这是抽奖域对账读模型的实现（不是 domain 端口）：case 只通过
 * {@link IDrawReconQueryService} 取数，domain 不认识库存表列。
 */
@Component
@RequiredArgsConstructor
public class DrawReconQueryService implements IDrawReconQueryService {

    private final IAwardReconQueryDao awardReconQueryDao;
    private final IDrawRecordReconQueryDao drawRecordReconQueryDao;

    @Override
    public List<String> listLedgerAwardIds(String cursorAwardId, int limit) {
        return awardReconQueryDao.listLedgerAwardIds(cursorAwardId, limit);
    }

    @Override
    public List<AwardPrereserveConservationVO> queryPrereserveConservation(LocalDateTime before,
                                                                               List<String> awardIds) {
        return awardReconQueryDao.queryPrereserveConservation(before, awardIds).stream()
                .map(this::toPrereserveConservation).toList();
    }

    @Override
    public List<UnmatchedDeductDetailVO> listUnmatchedDeductDetails(LocalDateTime before, List<String> awardIds) {
        return awardReconQueryDao
                .listUnmatchedDeductDetails(before, AwardBizTypes.LOTTERY_DRAW, awardIds).stream()
                .map(this::toUnmatchedDeductDetail).toList();
    }

    @Override
    public List<RecordMissingDeductVO> listRecordsMissingDeduct(LocalDateTime before, List<String> awardIds) {
        return drawRecordReconQueryDao
                .listRecordsMissingDeduct(before, AwardBizTypes.LOTTERY_DRAW, awardIds).stream()
                .map(this::toRecordMissingDeduct).toList();
    }

    private AwardPrereserveConservationVO toPrereserveConservation(AwardPrereserveConservationPO awardPrereserveConservationPO) {
        return AwardPrereserveConservationVO.builder()
                .awardId(awardPrereserveConservationPO.getAwardId())
                .waitingQty(awardPrereserveConservationPO.getWaitingQty())
                .issuedQty(awardPrereserveConservationPO.getIssuedQty())
                .deductedQty(awardPrereserveConservationPO.getDeductedQty())
                .build();
    }

    private UnmatchedDeductDetailVO toUnmatchedDeductDetail(UnmatchedDeductDetailPO unmatchedDeductDetailPO) {
        return UnmatchedDeductDetailVO.builder()
                .awardId(unmatchedDeductDetailPO.getAwardId())
                .bizId(unmatchedDeductDetailPO.getBizId())
                .quantity(unmatchedDeductDetailPO.getQuantity())
                .build();
    }

    private RecordMissingDeductVO toRecordMissingDeduct(RecordMissingDeductPO recordMissingDeductPO) {
        return RecordMissingDeductVO.builder()
                .recordId(recordMissingDeductPO.getRecordId())
                .invId(recordMissingDeductPO.getInvId())
                .userId(recordMissingDeductPO.getUserId())
                .build();
    }
}
