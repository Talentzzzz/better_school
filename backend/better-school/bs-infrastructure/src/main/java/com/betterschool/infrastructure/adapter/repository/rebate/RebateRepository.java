package com.betterschool.infrastructure.adapter.repository.rebate;

import com.betterschool.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.domain.rebate.model.entity.RebateClaimEntity;
import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.model.valobj.RebateTypeVO;
import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.betterschool.infrastructure.dao.rebate.IActionRebateClaimDao;
import com.betterschool.infrastructure.dao.rebate.IActionRebateConfigDao;
import com.betterschool.infrastructure.dao.rebate.po.ActionRebateClaim;
import com.betterschool.infrastructure.dao.rebate.po.ActionRebateConfigPO;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.PostRebateCreditMessage;
import com.betterschool.types.common.PostRebateTaskKeys;
import com.betterschool.types.rebate.RebateClaimState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 返利仓储实现：返利规则（action_rebate_config）与行为返利领取记录（action_rebate_claim）的读写，
 * 以及领取的一次原子用例。业务校验在域服务里。
 * <p>
 * 领取事务（{@link #claim}）只写本域两张表 + outbox 行，事务边界不出仓储（ADR-0016）：
 * 查重 → 插 claim → 插 credit outbox 收在一个 REQUIRED 本地事务里，提交后才同步发送 credit 消息。
 * 查重与撞唯一键后的重查一律 {@code SELECT ... FOR UPDATE}——REPEATABLE READ 下普通读看不见
 * 并发事务提交的新行，只有加锁读才能拿到最新已提交状态，避免重放被判成 DENIED / 重复插行。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RebateRepository implements IRebateRepository {

    private final IActionRebateConfigDao actionRebateConfigDao;
    private final IActionRebateClaimDao actionRebateClaimDao;
    private final ObjectMapper objectMapper;
    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final TransactionTemplate transactionTemplate;

    @Override
    public Integer findOpenRebateValue(String actionType, RebateTypeVO rebateType) {
        return actionRebateConfigDao.selectOpenRebateValue(actionType, rebateType.name());
    }

    @Override
    public List<RebateConfigEntity> listRebates() {
        return actionRebateConfigDao.selectRebates().stream().map(this::toEntity).toList();
    }

    @Override
    public RebateConfigEntity upsertRebate(RebateConfigEntity rebate) {
        ActionRebateConfigPO rebatePO = new ActionRebateConfigPO();
        rebatePO.setActionType(rebate.getActionType());
        rebatePO.setRebateType(rebate.getRebateType().name());
        rebatePO.setRebateName(rebate.getRebateName());
        rebatePO.setRebateDesc(rebate.getRebateDesc());
        rebatePO.setRebateValue(rebate.getRebateValue());
        rebatePO.setConfigId(rebate.getConfigId());
        rebatePO.setState(rebate.getState());
        actionRebateConfigDao.upsertRebate(rebatePO);
        return toEntity(actionRebateConfigDao.selectRebate(rebate.getActionType(), rebate.getRebateType().name()));
    }

    // ---------- 行为返利领取 ----------

    @Override
    public RebateClaimResultVO claim(RebateClaimEntity claimEntity) {
        // 事务内：查重 + 插 claim +（CLAIMED 时）插 credit outbox；事务提交前不产生任何外部副作用
        ClaimTxOutcome outcome = transactionTemplate.execute(status -> claimInTransaction(claimEntity));
        if (outcome == null) {
            throw new IllegalStateException("返利领取事务未返回结果：" + claimEntity.getRefId());
        }
        // 事务外同步发送，broker 确认后标记 SENT；失败留 PENDING 交 outboxScan 兜底
        if (outcome.creditOutbox() != null) {
            sendOutbox(outcome.creditOutbox());
        }
        return outcome.result();
    }

    /**
     * 领取事务体：同 ref 命中 → 返回已有快照（即使已 GRANTED，API 仍 CLAIMED）；同周期命中 → DENIED；
     * 否则插 claim 行 + credit outbox。并发撞唯一键时用加锁读重查，区分「同 ref 幂等」与「同周期 DENIED」。
     */
    private ClaimTxOutcome claimInTransaction(RebateClaimEntity claimEntity) {
        ActionRebateClaim existingRef = actionRebateClaimDao.selectByRef(
                claimEntity.getUserId(), claimEntity.getActionType(), claimEntity.getRefId());
        if (existingRef != null) {
            // 同一次行为重放：返回已有快照，不重复插 outbox、不发送
            return new ClaimTxOutcome(toClaimResult(existingRef), null);
        }
        ActionRebateClaim existingPeriod = actionRebateClaimDao.selectByUserActionPeriod(
                claimEntity.getUserId(), claimEntity.getActionType(), claimEntity.getPeriodKey());
        if (existingPeriod != null) {
            // 当天名额已被该用户的其他行为领取：不落行、不占名额
            return new ClaimTxOutcome(RebateClaimResultVO.denied(claimEntity.getRefId()), null);
        }
        try {
            actionRebateClaimDao.insert(toClaimPO(claimEntity));
        } catch (DuplicateKeyException e) {
            // 并发同 ref / 同周期撞唯一键：加锁重查最后一次已提交状态
            ActionRebateClaim racedRef = actionRebateClaimDao.selectByRef(
                    claimEntity.getUserId(), claimEntity.getActionType(), claimEntity.getRefId());
            if (racedRef != null) {
                return new ClaimTxOutcome(toClaimResult(racedRef), null);
            }
            ActionRebateClaim racedPeriod = actionRebateClaimDao.selectByUserActionPeriod(
                    claimEntity.getUserId(), claimEntity.getActionType(), claimEntity.getPeriodKey());
            if (racedPeriod != null) {
                return new ClaimTxOutcome(RebateClaimResultVO.denied(claimEntity.getRefId()), null);
            }
            // 非 uk_user_action_ref / uk_user_action_period 的冲突（如 claim_id 撞号）不是预期分支
            throw e;
        }
        // CLAIMED：入账任务与领取行同事务落库，提交后由调用方同步发送
        MqOutbox creditOutbox = MqOutbox.pending(
                PostRebateTaskKeys.creditOf(claimEntity.getRefId()),
                Constants.POST_REBATE_CREDIT_TOPIC,
                new PostRebateCreditMessage(
                        claimEntity.getClaimId(),
                        claimEntity.getUserId(),
                        claimEntity.getRefId(),
                        claimEntity.getPointsValue(),
                        claimEntity.getDrawCountValue()),
                objectMapper);
        mqOutboxDao.insert(creditOutbox);
        return new ClaimTxOutcome(
                RebateClaimResultVO.claimed(
                        claimEntity.getClaimId(),
                        claimEntity.getRefId(),
                        claimEntity.getPointsValue(),
                        claimEntity.getDrawCountValue()),
                creditOutbox);
    }

    @Override
    public RebateClaimResultVO findClaimByRef(String userId, String actionType, String refId) {
        ActionRebateClaim claim = actionRebateClaimDao.selectByRefNoLock(userId, actionType, refId);
        return claim == null ? null : toClaimResult(claim);
    }

    @Override
    public RebateClaimResultVO findClaimByClaimId(String claimId) {
        ActionRebateClaim claim = actionRebateClaimDao.selectByClaimId(claimId);
        return claim == null ? null : toClaimResult(claim);
    }

    @Override
    public void markGranted(String claimId) {
        actionRebateClaimDao.markGranted(claimId);
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

    private RebateConfigEntity toEntity(ActionRebateConfigPO rebatePO) {
        RebateConfigEntity rebate = new RebateConfigEntity();
        rebate.setId(rebatePO.getId());
        rebate.setActionType(rebatePO.getActionType());
        rebate.setRebateType(RebateTypeVO.valueOf(rebatePO.getRebateType()));
        rebate.setRebateName(rebatePO.getRebateName());
        rebate.setRebateDesc(rebatePO.getRebateDesc());
        rebate.setRebateValue(rebatePO.getRebateValue());
        rebate.setConfigId(rebatePO.getConfigId());
        rebate.setState(rebatePO.getState());
        rebate.setCreateTime(rebatePO.getCreateTime());
        rebate.setUpdateTime(rebatePO.getUpdateTime());
        return rebate;
    }

    private ActionRebateClaim toClaimPO(RebateClaimEntity claimEntity) {
        ActionRebateClaim claim = new ActionRebateClaim();
        claim.setClaimId(claimEntity.getClaimId());
        claim.setUserId(claimEntity.getUserId());
        claim.setActionType(claimEntity.getActionType());
        claim.setPeriodKey(claimEntity.getPeriodKey());
        claim.setRefId(claimEntity.getRefId());
        claim.setPointsValue(claimEntity.getPointsValue());
        claim.setDrawCountValue(claimEntity.getDrawCountValue());
        claim.setState(claimEntity.getState().name());
        claim.setCreateTime(claimEntity.getCreateTime());
        claim.setUpdateTime(claimEntity.getUpdateTime());
        return claim;
    }

    /** 领取行 → 结果对象：GRANTED 行重放时 API 仍返回 CLAIMED，persistedState 回真实值 */
    private RebateClaimResultVO toClaimResult(ActionRebateClaim claim) {
        RebateClaimState state = RebateClaimState.valueOf(claim.getState());
        return switch (state) {
            case GRANTED -> RebateClaimResultVO.granted(
                    claim.getClaimId(), claim.getRefId(), claim.getPointsValue(), claim.getDrawCountValue());
            case CLAIMED -> RebateClaimResultVO.claimed(
                    claim.getClaimId(), claim.getRefId(), claim.getPointsValue(), claim.getDrawCountValue());
        };
    }

    /** 事务内产出的两件东西：给调用方的领取结果 + 待提交后发送的 credit outbox（非 CLAIMED 为 null） */
    private record ClaimTxOutcome(RebateClaimResultVO result, MqOutbox creditOutbox) {
    }
}
