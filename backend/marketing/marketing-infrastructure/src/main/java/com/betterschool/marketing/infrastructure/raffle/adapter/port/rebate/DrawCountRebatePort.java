package com.betterschool.marketing.infrastructure.raffle.adapter.port.rebate;

import com.betterschool.marketing.domain.raffle.adapter.port.rebate.IDrawCountRebatePort;
import com.betterschool.marketing.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.marketing.domain.rebate.model.valobj.RebateTypeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 抽奖域次数返利只读端口实现：只读返利域仓储取数。
 * 「查哪一种 rebate_type」由本适配器钉死（DRAW_COUNT）——抽奖域因此不认识返利域的枚举；
 * 返利域自己的写面归 {@code IRebateService}，本端口不碰。
 */
@Component
@RequiredArgsConstructor
public class DrawCountRebatePort implements IDrawCountRebatePort {

    private final IRebateRepository rebateRepository;

    @Override
    public Integer findOpenRebateValue(String actionType) {
        return rebateRepository.findOpenRebateValue(actionType, RebateTypeVO.DRAW_COUNT);
    }
}
