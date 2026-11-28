package com.betterschool.domain.rebate.service;

import com.betterschool.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.entity.RebateOrderEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.model.valobj.RebateTypeEnum;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.id.IIdGenerator;
import com.betterschool.types.rebate.RebateActionTypeEnum;
import com.betterschool.types.rebate.RebateClaimStateEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 返利域服务：返利规则读写 + 行为返利同步领取（先按 ref 幂等查已有领取，未命中才读 open 规则构快照并调
 * 仓储 claim 一次，事务边界在仓储内）。业务校验在这里（repository 只管数据访问）——返利值非负、
 * actionType / rebateType / state 只收约定值；入参不合法抛 {@code CONFIG_PARAM_ERROR}，前端按码出 toast。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RebateServiceImpl implements IRebateService {

    /** 行为类型取值与 {@link RebateActionTypeEnum} 对齐；运营新增行为时枚举与此处同步放开 */
    private static final Set<String> ACTION_TYPES = Set.of(
            RebateActionTypeEnum.SIGN.getCode(),
            RebateActionTypeEnum.PAY.getCode(),
            RebateActionTypeEnum.POST.getCode());
    private static final Set<String> STATES = Set.of("open", "close");

    private final IRebateRepository rebateRepository;
    private final IIdGenerator idGenerator;

    @Override
    public List<RebateConfigEntity> listRebates() {
        return rebateRepository.listRebates();
    }

    @Override
    public RebateConfigEntity upsertRebateConfig(RebateConfigEntity rebateConfigEntity) {
        if (rebateConfigEntity == null) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR);
        }
        if (rebateConfigEntity.getActionType() == null
                || !ACTION_TYPES.contains(rebateConfigEntity.getActionType())) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR, "不支持的行为类型");
        }
        if (rebateConfigEntity.getRebateType() == null) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR, "不支持的返利类型");
        }
        if (rebateConfigEntity.getRebateName() == null || rebateConfigEntity.getRebateName().isBlank()
                || rebateConfigEntity.getRebateValue() == null || rebateConfigEntity.getRebateValue() < 0
                || rebateConfigEntity.getConfigId() == null) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR);
        }
        if (rebateConfigEntity.getState() == null || !STATES.contains(rebateConfigEntity.getState())) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR, "不支持的状态");
        }
        return rebateRepository.upsertRebateConfig(rebateConfigEntity);
    }

    @Override
    public RebateClaimResultVO claimActionRebate(String actionType, String userId, String refType,
                                                 String refId, String periodKey) {
        if (actionType == null || actionType.isBlank()
                || userId == null || userId.isBlank()
                || refType == null || refType.isBlank()
                || refId == null || refId.isBlank()
                || periodKey == null || periodKey.isBlank()) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR);
        }
        // 幂等优先：同一 ref 已有领取记录时直接返回已有快照，不读当前规则、不插任何行。
        // 规则可能在领取后被运营关闭；若先读规则会把重放误判成 NO_REBATE，违背「同一 ref 重放结果一致」。
        RebateClaimResultVO rebateClaimResultVO = rebateRepository.findClaimByRef(userId, actionType, refId);
        if (rebateClaimResultVO != null) {
            return rebateClaimResultVO;
        }
        // 首次领取才读两条 open 规则构快照：未配置 / 已关闭为 null，按 0 处理；两者都为 0 不占名额、不落库
        int pointsValue = valueOrZero(rebateRepository.findOpenRebateValue(actionType, RebateTypeEnum.POINTS));
        int drawCountValue = valueOrZero(rebateRepository.findOpenRebateValue(actionType, RebateTypeEnum.DRAW_COUNT));
        if (pointsValue == 0 && drawCountValue == 0) {
            return RebateClaimResultVO.noRebate(refId);
        }
        // 按返利类型拆行：快照为 0 的一行也落库，占住该类型的日限名额（落库即 GRANTED、不发入账消息）
        List<RebateOrderEntity> rebateOrderEntityList = List.of(
                buildRebateOrder(userId, actionType, refType, refId, periodKey, RebateTypeEnum.POINTS, pointsValue),
                buildRebateOrder(userId, actionType, refType, refId, periodKey, RebateTypeEnum.DRAW_COUNT, drawCountValue));
        return rebateRepository.claim(rebateOrderEntityList);
    }

    @Override
    public RebateClaimResultVO claimActionRebateOrPending(String actionType, String userId, String refType,
                                                          String refId, String periodKey) {
        // 行为已提交、领取只是尽力而为：异常吞成 CLAIM_ERROR 返回，行为侧领取任务保持未发送由 outboxScan 重放
        try {
            return claimActionRebate(actionType, userId, refType, refId, periodKey);
        } catch (Exception e) {
            log.warn("[RebateService] 同步领取异常，返回 CLAIM_ERROR 等待重放 actionType={} refType={} refId={}",
                    actionType, refType, refId, e);
            return RebateClaimResultVO.claimError(refId);
        }
    }

    @Override
    public void markGranted(String orderId) {
        rebateRepository.markGranted(orderId);
    }

    private RebateOrderEntity buildRebateOrder(String userId, String actionType, String refType, String refId,
                                               String periodKey, RebateTypeEnum rebateType, int rebateValue) {
        RebateOrderEntity rebateOrderEntity = new RebateOrderEntity();
        rebateOrderEntity.setOrderId(idGenerator.nextId());
        rebateOrderEntity.setUserId(userId);
        rebateOrderEntity.setActionType(actionType);
        rebateOrderEntity.setPeriodKey(periodKey);
        rebateOrderEntity.setRefType(refType);
        rebateOrderEntity.setRefId(refId);
        rebateOrderEntity.setRebateType(rebateType);
        rebateOrderEntity.setRebateValue(rebateValue);
        // 快照为 0 的行没有要发的东西，直接终态 GRANTED；有值的行等 credit 消费置 GRANTED
        rebateOrderEntity.setRebateClaimState(rebateValue > 0
                ? RebateClaimStateEnum.CLAIMED
                : RebateClaimStateEnum.GRANTED);
        return rebateOrderEntity;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
