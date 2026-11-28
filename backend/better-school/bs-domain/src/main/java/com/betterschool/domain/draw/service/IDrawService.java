package com.betterschool.domain.draw.service;

import com.betterschool.domain.draw.model.entity.DrawStrategyAwardEntity;
import com.betterschool.domain.draw.model.valobj.DrawAttemptVO;
import com.betterschool.domain.draw.model.valobj.DrawResultVO;
import com.betterschool.types.common.DrawReconAlertMessage;

/**
 * 抽奖主链路（票 13）：domain 负责本域内可完成的部分——幂等预检 → 活动校验 → 次数扣减 →
 * 责任链出奖（未预热降级 DB 直算）；库存预扣跨域，由 case 层编排；预扣完成后回到本服务落库。
 * <p>
 * 拆成 prepare / finish 两段是为了让库存预扣留在 case 层：domain 不再认识奖品域，也不再使用任何跨域 port。
 */
public interface IDrawService {

    /**
     * 单抽前半段：幂等预检 + 活动校验 + 扣次 + 责任链出奖。
     * 幂等键 = (userId, token) → record_id 的确定性映射：已落库记录直接回放在 {@code replayResult}，
     * case 拿到后原样返回即可。
     */
    DrawAttemptVO prepareDraw(String userId, String token);

    /**
     * 孤儿预扣收敛专用重放前半段：不扣次数（对账单存在即证明扣次已完成），
     * 只重跑「活动校验 + 责任链出奖」。活动不可重放抛 {@code LOTTERY_ACTIVITY_*}。
     */
    DrawAttemptVO prepareReplay(String userId, String recordId);

    /**
     * 当前是否有可重放的进行中活动（孤儿预扣收敛前的预判，供编排方选择「重放」还是「回补 + 退还」）。
     * 预判与重放之间活动可能被关闭，重放仍会抛 {@code LOTTERY_ACTIVITY_*}。
     */
    boolean hasOpenActivity();

    /**
     * 对账告警投递（经本域仓储 outbox → MQ → 单体消费落通知）：尽力而为——投递失败只告警不抛，
     * 差错已登记，下一轮对账会重新点名。
     */
    void sendReconAlert(DrawReconAlertMessage drawReconAlertMessage);

    /**
     * 后半段：库存预扣完成后的最终奖品位落库（draw_record + 明细 + 中奖事件同事务）。
     * 并发同 token 撞 uk 时回放已落记录。
     */
    DrawResultVO finishDraw(DrawAttemptVO drawAttemptVO, DrawStrategyAwardEntity drawStrategyAwardEntity);
}
