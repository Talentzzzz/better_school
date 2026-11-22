package com.betterschool.domain.raffle.service;

import com.betterschool.domain.raffle.model.valobj.DrawResultVO;

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

    /**
     * 孤儿预扣收敛专用重放（对账 job 调，不对外暴露）：以原 {@code recordId} 重放
     * 「决策 → 库存预扣 → 落库」后半段。**不扣次数**——对账单的存在即证明扣次已随主链路完成过；
     * 预扣撞唯一键按「已扣」回放（同 token 并发双击的同一幂等口径）。决策重跑可能得到与
     * 原决策不同的奖品，**接受该漂移**（原奖品的预扣由对账方按回补口径归还）。
     * <p>
     * 活动不可重放（查无进行中活动 / 已结束 / 未开始）抛 {@code LOTTERY_ACTIVITY_*}，
     * 调用方据此转「库存回补 + 次数退还」路径；其余失败按可重试抛出。
     */
    DrawResultVO replayDrawAfterPrereserve(String userId, String recordId);
}
