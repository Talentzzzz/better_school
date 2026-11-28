package com.betterschool.domain.draw.service;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.entity.DrawActivityEntity;
import com.betterschool.domain.draw.model.valobj.ActivityInfoVO;
import com.betterschool.domain.draw.model.valobj.DrawChanceVO;
import com.betterschool.domain.draw.model.valobj.DrawRecordVO;
import com.betterschool.domain.draw.model.valobj.PrizeCatalogVO;
import com.betterschool.domain.draw.model.valobj.UserPrizeVO;
import com.betterschool.domain.draw.service.drawcount.IDrawCountAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 抽奖读侧实现（票 13）：全部落 draw_* 新表；次数余额与次数有效期同属抽奖域（抽奖次数与抽奖同域），
 * 直接读域内次数服务与仓储，不再给他域开 port。
 */
@Service
@RequiredArgsConstructor
public class DrawQueryServiceImpl implements IDrawQueryService {

    private final IDrawRepository drawRepository;
    private final IDrawCountAccountService drawCountAccountService;

    @Override
    public ActivityInfoVO getActivity() {
        DrawActivityEntity drawActivityEntity = drawRepository.queryCurrentActivity();
        if (drawActivityEntity == null) {
            return null;
        }
        ActivityInfoVO activityInfoVO = new ActivityInfoVO();
        activityInfoVO.setActivityId(drawActivityEntity.getActivityId());
        activityInfoVO.setName(drawActivityEntity.getActivityName());
        activityInfoVO.setStartAt(drawActivityEntity.getBeginDateTime());
        activityInfoVO.setEndAt(drawActivityEntity.getEndDateTime());
        activityInfoVO.setState(drawActivityEntity.getState());
        activityInfoVO.setDrawCountValidDays(drawCountAccountService.getValidDays());
        return activityInfoVO;
    }

    @Override
    public List<PrizeCatalogVO> listPrizes() {
        DrawActivityEntity drawActivityEntity = drawRepository.queryCurrentActivity();
        if (drawActivityEntity == null) {
            return List.of();
        }
        return drawRepository.queryPrizeCatalog(drawActivityEntity.getStrategyId());
    }

    @Override
    public boolean existsAward(String awardId) {
        return drawRepository.existsAward(awardId);
    }

    @Override
    public DrawChanceVO getDrawChances(String userId) {
        return new DrawChanceVO(drawCountAccountService.getBalance(userId), drawRepository.countTodayDrawn(userId));
    }

    @Override
    public long countDrawRecords(String userId) {
        return drawRepository.countDrawRecords(userId);
    }

    @Override
    public List<DrawRecordVO> listDrawRecords(String userId, int page, int pageSize) {
        return drawRepository.listDrawRecords(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countMyPrizes(String userId, List<String> states) {
        return drawRepository.countMyPrizes(userId, states);
    }

    @Override
    public List<UserPrizeVO> listMyPrizes(String userId, List<String> states, int page, int pageSize) {
        return drawRepository.listMyPrizes(userId, states, (page - 1) * pageSize, pageSize);
    }
}
