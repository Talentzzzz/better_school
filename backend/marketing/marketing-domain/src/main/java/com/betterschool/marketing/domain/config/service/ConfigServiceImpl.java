package com.betterschool.marketing.domain.config.service;

import com.betterschool.marketing.domain.config.adapter.repository.IConfigRepository;
import com.betterschool.marketing.domain.config.model.entity.RebateConfigEntity;
import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 配置域服务：纯配置读写，无桶账务。业务校验在这里（repository 只管数据访问）——
 * 天数必须为正、返利值非负、rebateType / state 只收枚举值；
 * 入参不合法抛 {@code CONFIG_PARAM_ERROR}，前端按码出 toast。
 */
@Service
@RequiredArgsConstructor
public class ConfigServiceImpl implements IConfigService {

    /** sql.md 表注释里的行为类型；运营新增行为时此处同步放开 */
    private static final Set<String> ACTION_TYPES = Set.of("sign", "pay");
    private static final Set<String> STATES = Set.of("open", "close");

    private final IConfigRepository configRepository;

    @Override
    public int getPointsValidDays() {
        return configRepository.pointsValidDays();
    }

    @Override
    public void updatePointsValidDays(int days) {
        requirePositive(days);
        configRepository.updatePointsValidDays(days);
    }

    @Override
    public int getDrawCountValidDays() {
        return configRepository.drawCountValidDays();
    }

    @Override
    public void updateDrawCountValidDays(int days) {
        requirePositive(days);
        configRepository.updateDrawCountValidDays(days);
    }

    @Override
    public List<RebateConfigEntity> listRebates() {
        return configRepository.listRebates();
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
        return configRepository.upsertRebate(rebate);
    }

    private static void requirePositive(int days) {
        if (days <= 0) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR, "有效期天数必须为正");
        }
    }
}
