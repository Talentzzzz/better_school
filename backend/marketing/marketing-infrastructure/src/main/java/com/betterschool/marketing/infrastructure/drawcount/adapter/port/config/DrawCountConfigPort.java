package com.betterschool.marketing.infrastructure.drawcount.adapter.port.config;

import com.betterschool.marketing.domain.config.adapter.repository.IConfigRepository;
import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;
import com.betterschool.marketing.domain.drawcount.adapter.port.config.IDrawCountConfigPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 抽奖次数域配置只读端口实现：只读配置域仓储取数，判据留在次数域。
 * 配置域自己的写面归 {@code IConfigService}，本端口不碰。
 */
@Component
@RequiredArgsConstructor
public class DrawCountConfigPort implements IDrawCountConfigPort {

    private final IConfigRepository configRepository;

    @Override
    public int drawCountValidDays() {
        return configRepository.drawCountValidDays();
    }

    @Override
    public Integer findOpenRebateValue(String actionType, RebateTypeVO rebateType) {
        return configRepository.findOpenRebateValue(actionType, rebateType);
    }
}
