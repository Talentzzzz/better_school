package com.betterschool.marketing.infrastructure.points.adapter.port.config;

import com.betterschool.marketing.domain.config.adapter.repository.IConfigRepository;
import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;
import com.betterschool.marketing.domain.points.adapter.port.config.IPointsConfigPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 积分域配置只读端口实现：只读配置域仓储取数，判据留在积分域。
 * 配置域自己的写面归 {@code IConfigService}，本端口不碰。
 */
@Component
@RequiredArgsConstructor
public class PointsConfigPort implements IPointsConfigPort {

    private final IConfigRepository configRepository;

    @Override
    public int pointsValidDays() {
        return configRepository.pointsValidDays();
    }

    @Override
    public Integer findOpenRebateValue(String actionType, RebateTypeVO rebateType) {
        return configRepository.findOpenRebateValue(actionType, rebateType);
    }
}
