package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordDetailEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordActionVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordStateVO;
import com.betterschool.marketing.domain.raffle.model.valobj.ShippingPlanVO;
import com.betterschool.marketing.domain.raffle.model.valobj.ShippingResultVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发货状态机实现（票 14）：全图、幂等口径与两步编排的原因见 {@link IRaffleShippingService}。
 * 本类只碰 draw_record / draw_record_detail 两表；库存页的 {@code confirmIssued} 由 case 层按计划补调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleShippingServiceImpl implements IRaffleShippingService {

    private static final String AWARD_TYPE_PHYSICAL = "PHYSICAL";
    private static final String AWARD_TYPE_THANKS = "THANKS";

    private final IRaffleRepository raffleRepository;

    @Override
    public ShippingPlanVO planShipAfterDraw(String userId, String recordId) {
        DrawRecordEntity record = raffleRepository.queryRecordByRecordId(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            // 本服务自有事件回放却查无此记录（或归属不符）：不是错误路径，记日志跳过
            log.warn("[RaffleShipping] 中奖事件无对应抽奖记录，跳过 userId={} recordId={}", userId, recordId);
            return new ShippingPlanVO(recordId, null, null, false);
        }
        if (!DrawRecordStateVO.PENDING.equals(record.getState())) {
            // 已终态或已推进过（重复投递 / 并发）：状态机不回头
            log.info("[RaffleShipping] 记录不在待兑换，跳过推进 recordId={} state={}", recordId, record.getState());
            return new ShippingPlanVO(recordId, null, null, false);
        }
        if (AWARD_TYPE_THANKS.equals(record.getAwardType())) {
            // THANKS 落库即已完成（票 13），事件也本就不发；真收到就只记日志，不改状态
            log.info("[RaffleShipping] 谢谢参与档无发货动作，跳过 recordId={}", recordId);
            return new ShippingPlanVO(recordId, null, null, false);
        }

        boolean physical = AWARD_TYPE_PHYSICAL.equals(record.getAwardType());
        // 实物档：把「该先确认什么」在这里算出来（本方法只读，不碰状态）——见接口注释的两步编排
        ShippingPlanVO.QuotaConfirmVO quotaToConfirm = physical
                ? new ShippingPlanVO.QuotaConfirmVO(record.getInvId(), 1L) : null;
        log.info("[RaffleShipping] 中奖事件处置计划 recordId={} awardType={} confirmQuota={}",
                recordId, record.getAwardType(), quotaToConfirm != null);
        return new ShippingPlanVO(recordId, record.getAwardType(), quotaToConfirm, true);
    }

    @Override
    public ShippingResultVO advanceShipAfterDraw(String userId, ShippingPlanVO plan) {
        if (plan == null || !plan.isActive()) {
            return notAdvanced(plan == null ? null : plan.getRecordId());
        }
        boolean physical = AWARD_TYPE_PHYSICAL.equals(plan.getAwardType());
        // 实物：待兑换 → 已发货（一步到发货态，兑换确认已由调用方先行完成）；
        // 虚拟 / 次数：待兑换 → 已兑换 → 已完成（同一事务两步，入账已由积分域消费同事件完成）
        List<DrawRecordDetailEntity> details = physical
                ? List.of(detail(userId, plan.getRecordId(), DrawRecordActionVO.SHIP))
                : List.of(detail(userId, plan.getRecordId(), DrawRecordActionVO.EXCHANGE),
                        detail(userId, plan.getRecordId(), DrawRecordActionVO.COMPLETE));
        if (!raffleRepository.advanceState(userId, plan.getRecordId(), DrawRecordStateVO.PENDING, details)) {
            log.info("[RaffleShipping] 状态推进守卫落空（并发重复投递），recordId={}", plan.getRecordId());
            return notAdvanced(plan.getRecordId());
        }
        String targetState = DrawRecordActionVO.fromCode(details.get(details.size() - 1).getType()).getTargetState();
        log.info("[RaffleShipping] 中奖事件推进完成 recordId={} awardType={} state={}",
                plan.getRecordId(), plan.getAwardType(), targetState);
        return new ShippingResultVO(plan.getRecordId(), targetState, true);
    }

    @Override
    public ShippingResultVO confirmReceived(String userId, String recordId) {
        DrawRecordEntity record = raffleRepository.queryRecordByRecordId(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            // 不存在与不是本人的对外同码：不泄漏「这条记录存在但不是你的」
            throw new AppException(ResponseCode.PRIZE_NOT_RECEIVABLE);
        }
        if (DrawRecordStateVO.COMPLETED.equals(record.getState())) {
            // 幂等：重复点确认收货直接返回当前态，不再落明细
            return completed(recordId, false);
        }
        if (!DrawRecordStateVO.SHIPPED.equals(record.getState())) {
            throw new AppException(ResponseCode.PRIZE_NOT_RECEIVABLE);
        }
        boolean advanced = raffleRepository.advanceState(userId, recordId,
                DrawRecordStateVO.SHIPPED, List.of(detail(userId, recordId, DrawRecordActionVO.COMPLETE)));
        if (!advanced) {
            // 并发双击：另一笔已推进，按幂等返回当前态
            return completed(recordId, false);
        }
        log.info("[RaffleShipping] 确认收货完成 recordId={}", recordId);
        return completed(recordId, true);
    }

    private DrawRecordDetailEntity detail(String userId, String recordId, DrawRecordActionVO action) {
        LocalDateTime now = LocalDateTime.now();
        DrawRecordDetailEntity detail = new DrawRecordDetailEntity();
        detail.setUserId(userId);
        detail.setRecordId(recordId);
        detail.setType(action.getCode());
        detail.setCreateTime(now);
        detail.setUpdateTime(now);
        return detail;
    }

    private ShippingResultVO notAdvanced(String recordId) {
        return new ShippingResultVO(recordId, null, false);
    }

    /** 确认收货的三种出参（已终态 / 并发败者 / 本次推进）共用一条构造，只是 {@code advanced} 不同 */
    private ShippingResultVO completed(String recordId, boolean advanced) {
        return new ShippingResultVO(recordId, DrawRecordStateVO.COMPLETED, advanced);
    }
}
