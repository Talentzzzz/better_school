package com.betterschool.marketing.domain.rebate.service;

import com.betterschool.marketing.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.marketing.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 返利域服务：纯返利规则读写，无桶账务、不碰有效期。业务校验在这里（repository 只管数据访问）——
 * 返利值非负、actionType / rebateType / state 只收约定值；
 * 入参不合法抛 {@code CONFIG_PARAM_ERROR}，前端按码出 toast。
 */
@Service
@RequiredArgsConstructor
public class RebateServiceImpl implements IRebateService {

    /** sql.md 表注释里的行为类型；运营新增行为时此处同步放开 */
    private static final Set<String> ACTION_TYPES = Set.of("sign", "pay");
    private static final Set<String> STATES = Set.of("open", "close");

    private final IRebateRepository rebateRepository;

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
}
