package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.adapter.port.config.IRaffleConfigPort;
import com.betterschool.marketing.domain.raffle.adapter.port.drawcount.IDrawCountPort;
import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.entity.DrawActivityEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.ActivityInfoVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawChanceVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordVO;
import com.betterschool.marketing.domain.raffle.model.valobj.PrizeCatalogVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UserPrizeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 抽奖读侧实现（票 13）：全部落 draw_* 新表；次数余额经 {@link IDrawCountPort} 读次数域，
 * 有效期配置经 {@link IRaffleConfigPort} 只读配置域（一域一仓储：域层不认识他域仓储）。
 */
@Service
@RequiredArgsConstructor
public class RaffleQueryServiceImpl implements IRaffleQueryService {

    private final IRaffleRepository raffleRepository;
    private final IRaffleConfigPort raffleConfigPort;
    private final IDrawCountPort drawCountPort;

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
        vo.setDrawCountValidDays(raffleConfigPort.drawCountValidDays());
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
        return new DrawChanceVO(drawCountPort.getBalance(userId), raffleRepository.countTodayDrawn(userId));
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
