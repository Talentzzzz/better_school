package com.betterschool.infrastructure.adapter.repository.rebate;

import com.betterschool.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.entity.RebateOrderEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimItemVO;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.model.valobj.RebateTypeEnum;
import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.betterschool.infrastructure.dao.rebate.IActionRebateConfigDao;
import com.betterschool.infrastructure.dao.rebate.IActionRebateOrderDao;
import com.betterschool.infrastructure.dao.rebate.po.ActionRebateConfigPO;
import com.betterschool.infrastructure.dao.rebate.po.ActionRebateOrder;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.PostRebateCreditMessage;
import com.betterschool.types.common.PostRebateTaskKeys;
import com.betterschool.types.rebate.RebateClaimStateEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 返利仓储实现：返利规则（action_rebate_config）与行为返利订单（action_rebate_order）的读写，
 * 以及领取的一次原子用例。业务校验在域服务里。
 * <p>
 * 领取事务（{@link #claim}）只写本域两张表 + outbox 行，事务边界不出仓储（ADR-0016）：
 * 查重 → 按返利类型插订单行 → 插 credit outbox 收在一个 REQUIRED 本地事务里，提交后才逐行同步发送。
 * 查重与撞唯一键后的重查一律 {@code SELECT ... FOR UPDATE}——REPEATABLE READ 下普通读看不见
 * 并发事务提交的新行，只有加锁读才能拿到最新已提交状态，避免重放被判成 DENIED / 重复插行。
 * <p>
 * 并发撞唯一键时异常必须抛出事务体让整组订单行回滚（不能只回滚半组），再由外层另起事务加锁重查。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RebateRepository implements IRebateRepository {

    private final IActionRebateConfigDao actionRebateConfigDao;
    private final IActionRebateOrderDao actionRebateOrderDao;
    private final ObjectMapper objectMapper;
    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final TransactionTemplate transactionTemplate;

    @Override
    public Integer findOpenRebateValue(String actionType, RebateTypeEnum rebateTypeVO) {
        return actionRebateConfigDao.selectOpenRebateValue(actionType, rebateTypeVO.name());
    }

    @Override
    public List<RebateConfigEntity> listRebates() {
        return actionRebateConfigDao.selectRebates().stream().map(this::toEntity).toList();
    }

    @Override
    public RebateConfigEntity upsertRebateConfig(RebateConfigEntity rebateConfigEntity) {
        ActionRebateConfigPO actionRebateConfigPO = new ActionRebateConfigPO();
        actionRebateConfigPO.setActionType(rebateConfigEntity.getActionType());
        actionRebateConfigPO.setRebateType(rebateConfigEntity.getRebateType().name());
        actionRebateConfigPO.setRebateName(rebateConfigEntity.getRebateName());
        actionRebateConfigPO.setRebateDesc(rebateConfigEntity.getRebateDesc());
        actionRebateConfigPO.setRebateValue(rebateConfigEntity.getRebateValue());
        actionRebateConfigPO.setConfigId(rebateConfigEntity.getConfigId());
        actionRebateConfigPO.setState(rebateConfigEntity.getState());
        actionRebateConfigDao.upsertRebate(actionRebateConfigPO);
        return toEntity(actionRebateConfigDao.selectRebate(
                rebateConfigEntity.getActionType(), rebateConfigEntity.getRebateType().name()));
    }

    // ---------- 行为返利订单 ----------

    @Override
    public RebateClaimResultVO claim(List<RebateOrderEntity> rebateOrderEntityList) {
        if (rebateOrderEntityList == null || rebateOrderEntityList.isEmpty()) {
            throw new IllegalArgumentException("返利订单不能为空");
        }
        RebateOrderEntity head = rebateOrderEntityList.get(0);
        ClaimTxOutcome claimTxOutcome;
        try {
            // 事务内：查重 + 插订单行 + 插 credit outbox；事务提交前不产生任何外部副作用
            claimTxOutcome = transactionTemplate.execute(status -> claimInTransaction(rebateOrderEntityList));
        } catch (DuplicateKeyException e) {
            // 并发撞唯一键：事务已整组回滚，另起事务加锁重查最后一次已提交状态
            claimTxOutcome = transactionTemplate.execute(status -> resolveAfterRace(head));
            if (claimTxOutcome == null) {
                throw e;
            }
        }
        if (claimTxOutcome == null) {
            throw new IllegalStateException("返利领取事务未返回结果：" + head.getRefId());
        }
        // 事务外逐行同步发送，broker 确认后标记 SENT；失败留 PENDING 交 outboxScan 兜底
        claimTxOutcome.creditOutboxes().forEach(this::sendOutbox);
        return claimTxOutcome.result();
    }

    /**
     * 领取事务体：同 ref 命中 → 返回已有快照（即使已 GRANTED，对外经 apiState() 仍报 CLAIMED）；
     * 同周期命中 → DENIED；否则按返利类型插订单行 + 每行一条 credit outbox。
     * 任何 DuplicateKeyException 原样上抛，让整组订单行回滚，由 {@link #claim} 另起事务重查。
     */
    private ClaimTxOutcome claimInTransaction(List<RebateOrderEntity> rebateOrderEntityList) {
        RebateOrderEntity head = rebateOrderEntityList.get(0);
        List<ActionRebateOrder> existingRef = actionRebateOrderDao.selectByRef(
                head.getUserId(), head.getActionType(), head.getRefId());
        if (!existingRef.isEmpty()) {
            // 同一次行为重放：返回已有快照，不重复插 outbox、不发送
            return new ClaimTxOutcome(toClaimResult(existingRef), List.of());
        }
        List<ActionRebateOrder> existingPeriod = actionRebateOrderDao.selectByUserActionPeriod(
                head.getUserId(), head.getActionType(), head.getPeriodKey());
        if (!existingPeriod.isEmpty()) {
            // 当天名额已被该用户的其他行为领取：不落行、不占名额
            return new ClaimTxOutcome(RebateClaimResultVO.denied(head.getRefId()), List.of());
        }
        List<MqOutbox> creditOutboxList = new ArrayList<>(rebateOrderEntityList.size());
        for (RebateOrderEntity rebateOrderEntity : rebateOrderEntityList) {
            actionRebateOrderDao.insert(toOrderPO(rebateOrderEntity));
            // 快照为 0 的行只占日限名额，没有要发的东西，不产生入账消息
            if (rebateOrderEntity.getRebateValue() > 0) {
                PostRebateCreditMessage postRebateCreditMessage = new PostRebateCreditMessage(
                        rebateOrderEntity.getOrderId(),
                        rebateOrderEntity.getUserId(),
                        rebateOrderEntity.getRefId(),
                        rebateOrderEntity.getRebateType().name(),
                        rebateOrderEntity.getRebateValue());
                creditOutboxList.add(MqOutbox.pending(
                        PostRebateTaskKeys.creditOf(rebateOrderEntity.getOrderId()),
                        Constants.POST_REBATE_CREDIT_TOPIC,
                        postRebateCreditMessage,
                        objectMapper));
            }
        }
        // CLAIMED：入账任务与本组订单行同事务落库，提交后由调用方逐行同步发送
        return new ClaimTxOutcome(claimedOf(rebateOrderEntityList), creditOutboxList);
    }

    /** 并发撞唯一键后的加锁重查：同 ref → 幂等快照；同周期 → DENIED；都不是返回 null（非预期冲突） */
    private ClaimTxOutcome resolveAfterRace(RebateOrderEntity rebateOrderEntity) {
        List<ActionRebateOrder> racedRef = actionRebateOrderDao.selectByRef(
                rebateOrderEntity.getUserId(), rebateOrderEntity.getActionType(), rebateOrderEntity.getRefId());
        if (!racedRef.isEmpty()) {
            return new ClaimTxOutcome(toClaimResult(racedRef), List.of());
        }
        List<ActionRebateOrder> racedPeriod = actionRebateOrderDao.selectByUserActionPeriod(
                rebateOrderEntity.getUserId(), rebateOrderEntity.getActionType(), rebateOrderEntity.getPeriodKey());
        if (!racedPeriod.isEmpty()) {
            return new ClaimTxOutcome(RebateClaimResultVO.denied(rebateOrderEntity.getRefId()), List.of());
        }
        return null;
    }

    @Override
    public RebateClaimResultVO findClaimByRef(String userId, String actionType, String refId) {
        List<ActionRebateOrder> actionRebateOrderList = actionRebateOrderDao.selectByRefNoLock(userId, actionType, refId);
        return actionRebateOrderList.isEmpty() ? null : toClaimResult(actionRebateOrderList);
    }

    @Override
    public RebateOrderEntity findOrderByOrderId(String orderId) {
        ActionRebateOrder actionRebateOrder = actionRebateOrderDao.selectByOrderId(orderId);
        return actionRebateOrder == null ? null : toOrderEntity(actionRebateOrder);
    }

    @Override
    public void markGranted(String orderId) {
        actionRebateOrderDao.markGranted(orderId);
    }

    /** 同步发送一行 outbox：成功标记 SENT，失败累计重试次数（到上限置死信），留给 outboxScan 兜底 */
    private void sendOutbox(MqOutbox mqOutbox) {
        boolean sent;
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(mqOutbox.getDestination(), mqOutbox.getPayload());
            sent = sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
            if (!sent) {
                log.warn("outbox 同步发送未确认 msgId={}, status={}", mqOutbox.getMsgId(),
                        sendResult == null ? null : sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.warn("outbox 同步发送失败 msgId={}", mqOutbox.getMsgId(), e);
            sent = false;
        }
        if (sent) {
            mqOutboxDao.markSentByMsgId(mqOutbox.getMsgId());
        } else if (mqOutbox.getRetryCount() + 1 >= MqOutbox.MAX_RETRY_COUNT) {
            mqOutboxDao.markDeadBatch(List.of(mqOutbox.getId()));
        } else {
            mqOutboxDao.incrementRetryBatch(List.of(mqOutbox.getId()));
        }
    }

    private RebateConfigEntity toEntity(ActionRebateConfigPO actionRebateConfigPO) {
        RebateConfigEntity rebateConfigEntity = new RebateConfigEntity();
        rebateConfigEntity.setId(actionRebateConfigPO.getId());
        rebateConfigEntity.setActionType(actionRebateConfigPO.getActionType());
        rebateConfigEntity.setRebateType(RebateTypeEnum.valueOf(actionRebateConfigPO.getRebateType()));
        rebateConfigEntity.setRebateName(actionRebateConfigPO.getRebateName());
        rebateConfigEntity.setRebateDesc(actionRebateConfigPO.getRebateDesc());
        rebateConfigEntity.setRebateValue(actionRebateConfigPO.getRebateValue());
        rebateConfigEntity.setConfigId(actionRebateConfigPO.getConfigId());
        rebateConfigEntity.setState(actionRebateConfigPO.getState());
        rebateConfigEntity.setCreateTime(actionRebateConfigPO.getCreateTime());
        rebateConfigEntity.setUpdateTime(actionRebateConfigPO.getUpdateTime());
        return rebateConfigEntity;
    }

    private ActionRebateOrder toOrderPO(RebateOrderEntity rebateOrderEntity) {
        if (!rebateOrderEntity.getRebateClaimState().persisted()) {
            // DENIED / NO_REBATE / CLAIM_ERROR 是 API 结果，永远不该落库
            throw new IllegalArgumentException("不可落库的领取状态：" + rebateOrderEntity.getRebateClaimState());
        }
        ActionRebateOrder actionRebateOrder = new ActionRebateOrder();
        actionRebateOrder.setOrderId(rebateOrderEntity.getOrderId());
        actionRebateOrder.setUserId(rebateOrderEntity.getUserId());
        actionRebateOrder.setActionType(rebateOrderEntity.getActionType());
        actionRebateOrder.setPeriodKey(rebateOrderEntity.getPeriodKey());
        actionRebateOrder.setRefType(rebateOrderEntity.getRefType());
        actionRebateOrder.setRefId(rebateOrderEntity.getRefId());
        actionRebateOrder.setRebateType(rebateOrderEntity.getRebateType().name());
        actionRebateOrder.setRebateValue(rebateOrderEntity.getRebateValue());
        actionRebateOrder.setState(rebateOrderEntity.getRebateClaimState().name());
        actionRebateOrder.setCreateTime(rebateOrderEntity.getCreateTime());
        actionRebateOrder.setUpdateTime(rebateOrderEntity.getUpdateTime());
        return actionRebateOrder;
    }

    private RebateOrderEntity toOrderEntity(ActionRebateOrder actionRebateOrder) {
        RebateOrderEntity rebateOrderEntity = new RebateOrderEntity();
        rebateOrderEntity.setOrderId(actionRebateOrder.getOrderId());
        rebateOrderEntity.setUserId(actionRebateOrder.getUserId());
        rebateOrderEntity.setActionType(actionRebateOrder.getActionType());
        rebateOrderEntity.setPeriodKey(actionRebateOrder.getPeriodKey());
        rebateOrderEntity.setRefType(actionRebateOrder.getRefType());
        rebateOrderEntity.setRefId(actionRebateOrder.getRefId());
        rebateOrderEntity.setRebateType(RebateTypeEnum.valueOf(actionRebateOrder.getRebateType()));
        rebateOrderEntity.setRebateValue(actionRebateOrder.getRebateValue());
        rebateOrderEntity.setRebateClaimState(RebateClaimStateEnum.valueOf(actionRebateOrder.getState()));
        rebateOrderEntity.setCreateTime(actionRebateOrder.getCreateTime());
        rebateOrderEntity.setUpdateTime(actionRebateOrder.getUpdateTime());
        return rebateOrderEntity;
    }

    /** 一组订单行 → 领取结果：按返利类型汇总成项列表；全部 GRANTED 时状态回 GRANTED，否则 CLAIMED */
    private RebateClaimResultVO toClaimResult(List<ActionRebateOrder> actionRebateOrderList) {
        List<RebateClaimItemVO> rebateClaimItemVOList = new ArrayList<>(actionRebateOrderList.size());
        boolean allGranted = true;
        for (ActionRebateOrder actionRebateOrder : actionRebateOrderList) {
            RebateClaimStateEnum rebateClaimState = RebateClaimStateEnum.valueOf(actionRebateOrder.getState());
            if (!rebateClaimState.persisted()) {
                throw new IllegalStateException("订单行出现非持久化状态：" + rebateClaimState);
            }
            if (rebateClaimState != RebateClaimStateEnum.GRANTED) {
                allGranted = false;
            }
            rebateClaimItemVOList.add(new RebateClaimItemVO(
                    RebateTypeEnum.valueOf(actionRebateOrder.getRebateType()), actionRebateOrder.getRebateValue()));
        }
        String refId = actionRebateOrderList.get(0).getRefId();
        return allGranted
                ? RebateClaimResultVO.granted(refId, rebateClaimItemVOList)
                : RebateClaimResultVO.claimed(refId, rebateClaimItemVOList);
    }

    /** 本次要落的一组订单行 → CLAIMED 结果（一个返利类型一项） */
    private RebateClaimResultVO claimedOf(List<RebateOrderEntity> rebateOrderEntityList) {
        List<RebateClaimItemVO> rebateClaimItemVOList = new ArrayList<>(rebateOrderEntityList.size());
        for (RebateOrderEntity rebateOrderEntity : rebateOrderEntityList) {
            rebateClaimItemVOList.add(new RebateClaimItemVO(
                    rebateOrderEntity.getRebateType(), rebateOrderEntity.getRebateValue()));
        }
        return RebateClaimResultVO.claimed(rebateOrderEntityList.get(0).getRefId(), rebateClaimItemVOList);
    }

    /** 事务内产出的两件东西：给调用方的领取结果 + 待提交后发送的 credit outbox（无入账行时为空） */
    private record ClaimTxOutcome(RebateClaimResultVO result, List<MqOutbox> creditOutboxes) {
    }
}
