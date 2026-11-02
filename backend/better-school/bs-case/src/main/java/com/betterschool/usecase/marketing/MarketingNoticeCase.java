package com.betterschool.usecase.marketing;

import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 营销事件落通知（营销票 09）：单体作为消费者订阅两个营销 topic，case 层复用
 * {@code NotificationService.sendSystem} 落 {@code notification} 表——营销侧不同步调单体，单体停 → 营销照跑。
 * <p>
 * 两类通知统一 {@code scope = BOTTOM_SYSTEM}（底部消息页 system tab，不进市集消息页）、
 * {@code ref_type = MARKETING}、type 取营销枚举（不复用 ORDER_EVENT）。
 * 幂等 = {@code notification.dedup_key}（营销票 04 定的 {@code MARKETING:...} 形状）：
 * broker 重投 / outbox 补发只落一条；业务异常不吞，上抛交 broker 重试（重放由 dedup_key 幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingNoticeCase {

    private static final String REF_TYPE_MARKETING = "MARKETING";

    /** 事件体 prizeType 字符串值（跨服务 wire 层有意用字符串，见 LotteryDrawnMessage） */
    private static final String PRIZE_TYPE_VIRTUAL = "VIRTUAL";
    private static final String PRIZE_TYPE_THANKS = "THANKS";

    private final INotificationService notificationService;

    /** 中奖：文案按 prizeType 呈现（事件体不含奖品名，不反查生产方）；谢谢参与按生产方约定不发事件，防御性跳过 */
    public void notifyLotteryDrawn(LotteryDrawnMessage message) {
        if (PRIZE_TYPE_THANKS.equals(message.getPrizeType())) {
            log.warn("[MarketingNoticeCase] 收到谢谢参与的中奖事件（生产方不应发出），跳过 recordId={}", message.getRecordId());
            return;
        }
        // 票 13 起抽奖域把虚拟档口径从 POINTS 改成 VIRTUAL；旧值仍认，避免在途消息落错文案
        String content = isVirtualPrize(message.getPrizeType())
                ? "恭喜您在抽奖中抽中 " + message.getPointsAmount() + " 积分，已发放至您的积分账户"
                : "恭喜您在抽奖中抽中实物奖品，可在活动页「我的奖品」中查看";
        notificationService.sendSystem(message.getUserId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.LOTTERY_WON,
                "抽奖中奖", content, REF_TYPE_MARKETING, message.getRecordId(),
                marketingDedupKey("DRAWN", message.getRecordId()));
        log.info("[MarketingNoticeCase] 中奖通知已落 recordId={} userId={} prizeType={}",
                message.getRecordId(), message.getUserId(), message.getPrizeType());
    }

    /** 虚拟积分档：票 13 起是 VIRTUAL，"POINTS" 是旧模型的在途值 */
    private boolean isVirtualPrize(String prizeType) {
        return PRIZE_TYPE_VIRTUAL.equals(prizeType) || "POINTS".equals(prizeType);
    }

    /** 积分变动：正文按 reasonType + 变动量 + 变动后余额呈现 */
    public void notifyPointsChanged(PointsChangedMessage message) {
        String reasonText = switch (message.getReasonType()) {
            case "LOTTERY_WIN" -> "抽奖中奖";
            case "REDEEM_DRAW_COUNT" -> "兑换抽奖次数";
            case "REDEEM_PRIZE" -> "兑换奖品";
            default -> "积分变动";
        };
        int delta = message.getDelta();
        String content = reasonText + "：积分 " + (delta >= 0 ? "+" : "") + delta
                + "，当前余额 " + message.getBalanceAfter();
        notificationService.sendSystem(message.getUserId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.POINTS_CHANGED,
                "积分变动", content, REF_TYPE_MARKETING, message.getRefId(),
                marketingDedupKey("POINTS", message.getReasonType(), message.getRefId()));
        log.info("[MarketingNoticeCase] 积分变动通知已落 userId={} delta={} reasonType={} refId={}",
                message.getUserId(), delta, message.getReasonType(), message.getRefId());
    }

    private String marketingDedupKey(Object... parts) {
        StringBuilder key = new StringBuilder("MARKETING");
        for (Object part : parts) {
            key.append(':').append(part);
        }
        return key.toString();
    }
}
