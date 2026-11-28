package com.betterschool.domain.draw.service.recon;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.entity.DrawOrphanPrereserveOrderEntity;
import com.betterschool.domain.draw.model.entity.DrawReconRoundEntity;
import com.betterschool.domain.draw.model.entity.DrawReconVarianceEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.types.draw.DrawOrphanPrereserveStateEnum;
import com.betterschool.types.draw.DrawReconVarianceStateEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖库存对账写面实现：本域仓储的薄封装 + 对账单 / 轮次实体的时刻填充，
 * 业务判据（何时种单、何时置死信、差错如何分流）由 case 层编排决定。
 */
@Service
@RequiredArgsConstructor
public class DrawReconServiceImpl implements IDrawReconService {

    private final IDrawRepository drawRepository;

    @Override
    public String queryUserIdByDrawCountRef(String refId) {
        return drawRepository.queryUserIdByDrawCountRef(refId);
    }

    @Override
    public boolean seedOrphanPrereserveOrder(String recordId, String awardId, String userId, Long quantity) {
        LocalDateTime now = LocalDateTime.now();
        DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity = new DrawOrphanPrereserveOrderEntity(
                null, recordId, awardId, userId, quantity,
                DrawOrphanPrereserveStateEnum.ORPHAN, 0, null, null, now, now);
        return drawRepository.insertOrphanPrereserveOrder(drawOrphanPrereserveOrderEntity);
    }

    @Override
    public List<DrawOrphanPrereserveOrderEntity> listDueOrphanPrereserveOrders(int limit) {
        return drawRepository.listDueOrphanPrereserveOrders(limit);
    }

    @Override
    public boolean claimOrphanPrereserveOrder(Long id, LocalDateTime nextRetryAt) {
        return drawRepository.claimOrphanPrereserveOrder(id, nextRetryAt);
    }

    @Override
    public void markOrphanPrereserveOrderDead(Long id) {
        drawRepository.markOrphanPrereserveOrderDead(id);
    }

    @Override
    public DrawRecordEntity queryRecordByRecordId(String recordId) {
        return drawRepository.queryRecordByRecordId(recordId);
    }

    @Override
    public boolean finishOrphanPrereserveOrder(String recordId) {
        return drawRepository.finishOrphanPrereserveOrder(recordId);
    }

    @Override
    public DrawOrphanPrereserveOrderEntity queryOrphanPrereserveOrderByRecordId(String recordId) {
        return drawRepository.queryOrphanPrereserveOrderByRecordId(recordId);
    }

    @Override
    public List<DrawOrphanPrereserveOrderEntity> listDeadOrphanPrereserveOrders(int limit) {
        return drawRepository.listDeadOrphanPrereserveOrders(limit);
    }

    @Override
    public void startReconRound(String roundNo) {
        LocalDateTime now = LocalDateTime.now();
        DrawReconRoundEntity drawReconRoundEntity = new DrawReconRoundEntity();
        drawReconRoundEntity.setRoundNo(roundNo);
        drawReconRoundEntity.setScanStartTime(now);
        drawReconRoundEntity.setCreateTime(now);
        drawReconRoundEntity.setUpdateTime(now);
        drawRepository.insertReconRound(drawReconRoundEntity);
    }

    @Override
    public boolean registerReconVariance(DrawReconVarianceEntity drawReconVarianceEntity) {
        return drawRepository.insertReconVariance(drawReconVarianceEntity);
    }

    @Override
    public List<DrawReconVarianceEntity> listPendingReconVariances() {
        return drawRepository.listPendingReconVariances();
    }

    @Override
    public void markReconVarianceFalseAlarm(Long id) {
        drawRepository.markReconVarianceFalseAlarm(id);
    }

    @Override
    public boolean confirmReconVariance(Long id) {
        return drawRepository.confirmReconVariance(id);
    }

    @Override
    public void markReconVarianceAutoFixed(Long id) {
        drawRepository.markReconVarianceAutoFixed(id);
    }

    @Override
    public long countReconVariancesByRound(String roundNo) {
        return drawRepository.countReconVariancesByRound(roundNo);
    }

    @Override
    public long countReconVariancesByRoundAndState(String roundNo, DrawReconVarianceStateEnum drawReconVarianceState) {
        return drawRepository.countReconVariancesByRoundAndState(roundNo, drawReconVarianceState);
    }

    @Override
    public void finishReconRound(String roundNo, int varianceTotal, int confirmedTotal, int autoFixedTotal) {
        drawRepository.finishReconRound(roundNo, LocalDateTime.now(), varianceTotal, confirmedTotal, autoFixedTotal);
    }
}
