package com.betterschool.domain.draw.model.aggregate;

import com.betterschool.domain.draw.model.entity.DrawRecordDetailEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.types.common.LotteryDrawnMessage;
import lombok.Data;

/**
 * 抽奖落库聚合：draw_record 行 + 同事务的 draw_record_detail(DRAW) 动作行 + 同事务的 outbox 中奖事件。
 * 「记录 + 首个动作 + 对外事件」恒一起写入（票 13 落库、票 14 补事件），故封装为聚合交给仓储
 * **一个事务**落三处——事件与业务写同事务是 outbox 口径的根：业务回滚时事件行随之消失，
 * 不会出现「没中奖却通知中奖」。
 */
@Data
public class DrawRecordAggregate {

    private DrawRecordEntity record;
    /** 首个动作明细，type 恒 = DRAW */
    private DrawRecordDetailEntity detail;
    /** 中奖事件（topic {@code bs-lottery-drawn}）；THANKS 档为 null（谢谢参与不发事件） */
    private LotteryDrawnMessage drawnEvent;
}
