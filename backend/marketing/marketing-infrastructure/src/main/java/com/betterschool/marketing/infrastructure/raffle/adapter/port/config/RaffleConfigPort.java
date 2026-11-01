package com.betterschool.marketing.infrastructure.raffle.adapter.port.config;

import com.betterschool.marketing.domain.config.adapter.repository.IConfigRepository;
import com.betterschool.marketing.domain.raffle.adapter.port.config.IRaffleConfigPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 抽奖域配置只读端口实现：只读配置域仓储取数，判据留在消费方域。
 * 配置域自己的写面归 {@code IConfigService}，本端口不碰。
 */
@Component
@RequiredArgsConstructor
public class RaffleConfigPort implements IRaffleConfigPort {

    private final IConfigRepository configRepository;

    @Override
    public int drawCountValidDays() {
        return configRepository.drawCountValidDays();
    }
}
