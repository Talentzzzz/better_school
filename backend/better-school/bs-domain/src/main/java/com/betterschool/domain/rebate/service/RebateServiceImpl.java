package com.betterschool.domain.rebate.service;

import com.betterschool.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.domain.rebate.model.entity.RebateClaimEntity;
import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.model.valobj.RebateTypeVO;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.id.IIdGenerator;
import com.betterschool.types.rebate.RebateActionType;
import com.betterschool.types.rebate.RebateClaimState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 返利域服务：返利规则读写 + 行为返利同步领取（先按 ref 幂等查已有领取，未命中才读 open 规则构快照并调
 * 仓储 claim 一次，事务边界在仓储内）。业务校验在这里（repository 只管数据访问）——返利值非负、
 * actionType / rebateType / state 只收约定值；入参不合法抛 {@code CONFIG_PARAM_ERROR}，前端按码出 toast。
 */
@Service
@RequiredArgsConstructor
public class RebateServiceImpl implements IRebateService {

    /** 行为类型取值与 {@link RebateActionType} 对齐；运营新增行为时枚举与此处同步放开 */
    private static final Set<String> ACTION_TYPES = Set.of(
            RebateActionType.SIGN.getCode(),
            RebateActionType.PAY.getCode(),
            RebateActionType.POST.getCode());
    private static final Set<String> STATES = Set.of("open", "close");

    private final IRebateRepository rebateRepository;
    private final IIdGenerator idGenerator;

    @Override
    public List<RebateConfigEntity> listRebates() {
        return rebateRepository.listRebates();
    }

    @Override
    public RebateConfigEntity upsertRebate(RebateConfigEntity rebate) {
        if (rebate == null) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR);
        }
        if (rebate.getActionType() == null || !ACTION_TYPES.contains(rebate.getActionType())) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR, "不支持的行为类型");
        }
        if (rebate.getRebateType() == null) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR, "不支持的返利类型");
        }
        if (rebate.getRebateName() == null || rebate.getRebateName().isBlank()
                || rebate.getRebateValue() == null || rebate.getRebateValue() < 0
                || rebate.getConfigId() == null) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR);
        }
        if (rebate.getState() == null || !STATES.contains(rebate.getState())) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR, "不支持的状态");
        }
        return rebateRepository.upsertRebate(rebate);
    }

    @Override
    public RebateClaimResultVO claimActionRebate(String actionType, String userId, String refId, String periodKey) {
        if (actionType == null || actionType.isBlank()
                || userId == null || userId.isBlank()
                || refId == null || refId.isBlank()
                || periodKey == null || periodKey.isBlank()) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR);
        }
        // 幂等优先：同一 ref 已有领取记录时直接返回已有快照，不读当前规则、不插任何行。
        // 规则可能在领取后被运营关闭；若先读规则会把重放误判成 NO_REBATE，违背「同一 ref 重放结果一致」。
        RebateClaimResultVO existingClaim = rebateRepository.findClaimByRef(userId, actionType, refId);
        if (existingClaim != null) {
            return existingClaim;
        }
        // 首次领取才读两条 open 规则构快照：未配置 / 已关闭为 null，按 0 处理；两者都为 0 不占名额、不落库
        int pointsValue = valueOrZero(rebateRepository.findOpenRebateValue(actionType, RebateTypeVO.POINTS));
        int drawCountValue = valueOrZero(rebateRepository.findOpenRebateValue(actionType, RebateTypeVO.DRAW_COUNT));
        if (pointsValue == 0 && drawCountValue == 0) {
            return RebateClaimResultVO.noRebate(refId);
        }
        RebateClaimEntity claimEntity = new RebateClaimEntity();
        claimEntity.setClaimId(idGenerator.nextId());
        claimEntity.setUserId(userId);
        claimEntity.setActionType(actionType);
        claimEntity.setPeriodKey(periodKey);
        claimEntity.setRefId(refId);
        claimEntity.setPointsValue(pointsValue);
        claimEntity.setDrawCountValue(drawCountValue);
        claimEntity.setState(RebateClaimState.CLAIMED);
        return rebateRepository.claim(claimEntity);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
