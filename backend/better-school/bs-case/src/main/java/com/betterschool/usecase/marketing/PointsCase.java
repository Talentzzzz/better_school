package com.betterschool.usecase.marketing;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.types.common.PointsChangedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 积分变动用例（营销事件落通知）：消费 {@code bs-points-changed}，按 reasonType + 变动量 + 变动后余额
 * 发布通知事件 {@code NotificationService.publish}——通知落库统一由 NotifyEventConsumer 消费 bs-notify-event 完成。
 * <p>
 * 统一 {@code scope = BOTTOM_SYSTEM}（底部消息页 system tab，不进市集消息页）、
 * {@code ref_type = MARKETING}。幂等 = {@code notification.dedup_key}（{@code MARKETING:POINTS:...}）：
 * broker 重投 / outbox 补发只落一条；业务异常不吞，上抛交 broker 重试（重放由 dedup_key 幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsCase {

    private static final String REF_TYPE_MARKETING = "MARKETING";

    private final INotificationService notificationService;

    /** 积分变动：正文按 reasonType + 变动量 + 变动后余额呈现 */
    public void notifyPointsChanged(PointsChangedMessage pointsChangedMessage) {
        String reasonText = switch (pointsChangedMessage.getReasonType()) {
            case "LOTTERY_WIN" -> "抽奖中奖";
            case "REBATE" -> "发帖返利";
            case "REDEEM_DRAW_COUNT" -> "兑换抽奖次数";
            case "REDEEM_PRIZE" -> "兑换奖品";
            default -> "积分变动";
        };
        int delta = pointsChangedMessage.getDelta();
        String content = reasonText + "：积分 " + (delta >= 0 ? "+" : "") + delta
                + "，当前余额 " + pointsChangedMessage.getBalanceAfter();
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(pointsChangedMessage.getUserId())
                .scope(NotificationScopeEnum.BOTTOM_SYSTEM.name())
                .type(NotificationTypeEnum.POINTS_CHANGED.name())
                .title("积分变动")
                .content(content)
                .refType(REF_TYPE_MARKETING)
                .refId(pointsChangedMessage.getRefId())
                .dedupKey(marketingDedupKey("POINTS", pointsChangedMessage.getReasonType(), pointsChangedMessage.getRefId()))
                .build());
        log.info("[PointsCase] 积分变动通知已落 userId={} delta={} reasonType={} refId={}",
                pointsChangedMessage.getUserId(), delta, pointsChangedMessage.getReasonType(), pointsChangedMessage.getRefId());
    }

    private String marketingDedupKey(Object... parts) {
        StringBuilder key = new StringBuilder("MARKETING");
        for (Object part : parts) {
            key.append(':').append(part);
        }
        return key.toString();
    }
}
