package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.model.valobj.ActivityInfoVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawChanceVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordVO;
import com.betterschool.marketing.domain.raffle.model.valobj.PrizeCatalogVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UserPrizeVO;

import java.util.List;

/**
 * 抽奖读侧（票 13）：活动信息 / 奖品目录 / 我的次数 / 中奖记录 / 我的奖品，全部落 draw_* 新表。
 * 无活动数据时活动与目录返回空（null / 空表），调用方自行决定展示。
 */
public interface IRaffleQueryService {

    /** 当前活动 + 次数有效期配置；无活动返回 null */
    ActivityInfoVO getActivity();

    /** 当前活动的奖品目录；无活动返回空表 */
    List<PrizeCatalogVO> listPrizes();

    /** available = 未过期次数桶 SUM(remain)，todayDrawn = 今日已抽数 */
    DrawChanceVO getDrawChances(String userId);

    /** 我的中奖记录（恒过滤 THANKS），create_time DESC */
    long countDrawRecords(String userId);

    List<DrawRecordVO> listDrawRecords(String userId, int page, int pageSize);

    /** 我的奖品（award_type != THANKS），states 为空 = 全部，create_time DESC */
    long countMyPrizes(String userId, List<String> states);

    List<UserPrizeVO> listMyPrizes(String userId, List<String> states, int page, int pageSize);
}
