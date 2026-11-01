package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.model.valobj.DrawResultVO;

/**
 * 抽奖主链路（票 13）：幂等预检 → 活动校验 → 次数扣减 → 责任链出奖（未预热降级 DB 直算）→
 * 库存预扣（不足走兜底奖品）→ draw_record + draw_record_detail(DRAW) 同事务落库。
 * 发放闭环（outbox / MQ / 状态机推进）归票 14，本链路只到「落库」。
 */
public interface IRaffleDrawService {

    /**
     * 单抽。幂等键 = (userId, token) → record_id 的确定性映射：同一 token 重放返回同一份结果，
     * 不重复扣次数、不重复预扣库存。
     */
    DrawResultVO draw(String userId, String token);
}
