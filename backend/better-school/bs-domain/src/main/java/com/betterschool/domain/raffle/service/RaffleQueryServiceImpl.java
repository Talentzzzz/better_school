package com.betterschool.domain.raffle.service;

import com.betterschool.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.domain.raffle.model.entity.DrawActivityEntity;
import com.betterschool.domain.raffle.model.valobj.ActivityInfoVO;
import com.betterschool.domain.raffle.model.valobj.DrawChanceVO;
import com.betterschool.domain.raffle.model.valobj.DrawRecordVO;
import com.betterschool.domain.raffle.model.valobj.PrizeCatalogVO;
import com.betterschool.domain.raffle.model.valobj.UserPrizeVO;
import com.betterschool.domain.raffle.service.drawcount.IDrawCountAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 抽奖读侧实现（票 13）：全部落 draw_* 新表；次数余额与次数有效期同属抽奖域（抽奖次数与抽奖同域），
 * 直接读域内次数服务与仓储，不再给他域开 port。
 */
@Service
@RequiredArgsConstructor
public class RaffleQueryServiceImpl implements IRaffleQueryService {

    private final IRaffleRepository raffleRepository;
    private final IDrawCountAccountService drawCountAccountService;

    @Override
    public ActivityInfoVO getActivity() {
        DrawActivityEntity activity = raffleRepository.queryCurrentActivity();
        if (activity == null) {
            return null;
        }
        ActivityInfoVO vo = new ActivityInfoVO();
        vo.setActivityId(activity.getActivityId());
        vo.setName(activity.getActivityName());
        vo.setStartAt(activity.getBeginDateTime());
        vo.setEndAt(activity.getEndDateTime());
        vo.setState(activity.getState());
        vo.setDrawCountValidDays(drawCountAccountService.getValidDays());
        return vo;
    }

    @Override
    public List<PrizeCatalogVO> listPrizes() {
        DrawActivityEntity activity = raffleRepository.queryCurrentActivity();
        if (activity == null) {
            return List.of();
        }
        return raffleRepository.queryPrizeCatalog(activity.getStrategyId());
    }

    @Override
    public DrawChanceVO getDrawChances(String userId) {
        return new DrawChanceVO(drawCountAccountService.getBalance(userId), raffleRepository.countTodayDrawn(userId));
    }

    @Override
    public long countDrawRecords(String userId) {
        return raffleRepository.countDrawRecords(userId);
    }

    @Override
    public List<DrawRecordVO> listDrawRecords(String userId, int page, int pageSize) {
        return raffleRepository.listDrawRecords(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countMyPrizes(String userId, List<String> states) {
        return raffleRepository.countMyPrizes(userId, states);
    }

    @Override
    public List<UserPrizeVO> listMyPrizes(String userId, List<String> states, int page, int pageSize) {
        return raffleRepository.listMyPrizes(userId, states, (page - 1) * pageSize, pageSize);
    }
}
