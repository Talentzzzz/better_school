package com.betterschool.domain.draw.service;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.entity.DrawRecordDetailEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.domain.draw.model.valobj.DrawRecordActionEnum;
import com.betterschool.domain.draw.model.valobj.DrawRecordStateVO;
import com.betterschool.domain.draw.model.valobj.ShippingPlanVO;
import com.betterschool.domain.draw.model.valobj.ShippingResultVO;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发货状态机实现（票 14）：全图、幂等口径与两步编排的原因见 {@link IDrawShippingService}。
 * 本类只碰 draw_record / draw_record_detail 两表；库存页的 {@code confirmIssued} 由 case 层按计划补调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawShippingServiceImpl implements IDrawShippingService {

    private static final String AWARD_TYPE_PHYSICAL = "PHYSICAL";
    private static final String AWARD_TYPE_THANKS = "THANKS";

    private final IDrawRepository drawRepository;

    @Override
    public ShippingPlanVO planShipAfterDraw(String userId, String recordId) {
        DrawRecordEntity drawRecordEntity = drawRepository.queryRecordByRecordId(recordId);
        if (drawRecordEntity == null || !drawRecordEntity.getUserId().equals(userId)) {
            // 本服务自有事件回放却查无此记录（或归属不符）：不是错误路径，记日志跳过
            log.warn("[DrawShipping] 中奖事件无对应抽奖记录，跳过 userId={} recordId={}", userId, recordId);
            return new ShippingPlanVO(recordId, null, null, false);
        }
        if (!DrawRecordStateVO.PENDING.equals(drawRecordEntity.getState())) {
            // 已终态或已推进过（重复投递 / 并发）：状态机不回头
            log.info("[DrawShipping] 记录不在待兑换，跳过推进 recordId={} state={}", recordId, drawRecordEntity.getState());
            return new ShippingPlanVO(recordId, null, null, false);
        }
        if (AWARD_TYPE_THANKS.equals(drawRecordEntity.getAwardType())) {
            // THANKS 落库即已完成（票 13），事件也本就不发；真收到就只记日志，不改状态
            log.info("[DrawShipping] 谢谢参与档无发货动作，跳过 recordId={}", recordId);
            return new ShippingPlanVO(recordId, null, null, false);
        }

        boolean physical = AWARD_TYPE_PHYSICAL.equals(drawRecordEntity.getAwardType());
        // 实物档：把「该先确认什么」在这里算出来（本方法只读，不碰状态）——见接口注释的两步编排
        ShippingPlanVO.QuotaConfirmVO quotaToConfirm = physical
                ? new ShippingPlanVO.QuotaConfirmVO(drawRecordEntity.getInvId(), 1L) : null;
        log.info("[DrawShipping] 中奖事件处置计划 recordId={} awardType={} confirmQuota={}",
                recordId, drawRecordEntity.getAwardType(), quotaToConfirm != null);
        return new ShippingPlanVO(recordId, drawRecordEntity.getAwardType(), quotaToConfirm, true);
    }

    @Override
    public ShippingResultVO advanceShipAfterDraw(String userId, ShippingPlanVO shippingPlanVO) {
        if (shippingPlanVO == null || !shippingPlanVO.isActive()) {
            return notAdvanced(shippingPlanVO == null ? null : shippingPlanVO.getRecordId());
        }
        boolean physical = AWARD_TYPE_PHYSICAL.equals(shippingPlanVO.getAwardType());
        // 实物：待兑换 → 已发货（一步到发货态，兑换确认已由调用方先行完成）；
        // 虚拟 / 次数：待兑换 → 已兑换 → 已完成（同一事务两步，入账已由积分域消费同事件完成）
        List<DrawRecordDetailEntity> details = physical
                ? List.of(detail(userId, shippingPlanVO.getRecordId(), DrawRecordActionEnum.SHIP))
                : List.of(detail(userId, shippingPlanVO.getRecordId(), DrawRecordActionEnum.EXCHANGE),
                        detail(userId, shippingPlanVO.getRecordId(), DrawRecordActionEnum.COMPLETE));
        if (!drawRepository.advanceState(userId, shippingPlanVO.getRecordId(), DrawRecordStateVO.PENDING, details)) {
            log.info("[DrawShipping] 状态推进守卫落空（并发重复投递），recordId={}", shippingPlanVO.getRecordId());
            return notAdvanced(shippingPlanVO.getRecordId());
        }
        String targetState = DrawRecordActionEnum.fromCode(details.get(details.size() - 1).getType()).getTargetState();
        log.info("[DrawShipping] 中奖事件推进完成 recordId={} awardType={} state={}",
                shippingPlanVO.getRecordId(), shippingPlanVO.getAwardType(), targetState);
        return new ShippingResultVO(shippingPlanVO.getRecordId(), targetState, true);
    }

    @Override
    public ShippingResultVO confirmReceived(String userId, String recordId) {
        DrawRecordEntity drawRecordEntity = drawRepository.queryRecordByRecordId(recordId);
        if (drawRecordEntity == null || !drawRecordEntity.getUserId().equals(userId)) {
            // 不存在与不是本人的对外同码：不泄漏「这条记录存在但不是你的」
            throw new AppException(ResponseCodeEnum.PRIZE_NOT_RECEIVABLE);
        }
        if (DrawRecordStateVO.COMPLETED.equals(drawRecordEntity.getState())) {
            // 幂等：重复点确认收货直接返回当前态，不再落明细
            return completed(recordId, false);
        }
        if (!DrawRecordStateVO.SHIPPED.equals(drawRecordEntity.getState())) {
            throw new AppException(ResponseCodeEnum.PRIZE_NOT_RECEIVABLE);
        }
        boolean advanced = drawRepository.advanceState(userId, recordId,
                DrawRecordStateVO.SHIPPED, List.of(detail(userId, recordId, DrawRecordActionEnum.COMPLETE)));
        if (!advanced) {
            // 并发双击：另一笔已推进，按幂等返回当前态
            return completed(recordId, false);
        }
        log.info("[DrawShipping] 确认收货完成 recordId={}", recordId);
        return completed(recordId, true);
    }

    private DrawRecordDetailEntity detail(String userId, String recordId, DrawRecordActionEnum drawRecordActionVO) {
        LocalDateTime now = LocalDateTime.now();
        DrawRecordDetailEntity drawRecordDetailEntity = new DrawRecordDetailEntity();
        drawRecordDetailEntity.setUserId(userId);
        drawRecordDetailEntity.setRecordId(recordId);
        drawRecordDetailEntity.setType(drawRecordActionVO.getCode());
        drawRecordDetailEntity.setCreateTime(now);
        drawRecordDetailEntity.setUpdateTime(now);
        return drawRecordDetailEntity;
    }

    private ShippingResultVO notAdvanced(String recordId) {
        return new ShippingResultVO(recordId, null, false);
    }

    /** 确认收货的三种出参（已终态 / 并发败者 / 本次推进）共用一条构造，只是 {@code advanced} 不同 */
    private ShippingResultVO completed(String recordId, boolean advanced) {
        return new ShippingResultVO(recordId, DrawRecordStateVO.COMPLETED, advanced);
    }
}
