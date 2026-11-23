package com.betterschool.usecase.marketing;

import com.betterschool.types.common.LotteryDrawnMessage;
import com.betterschool.types.common.PointsChangedMessage;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 营销事件落通知（营销票 09）：单体作为消费者订阅两个营销 topic，case 层发布通知事件
 * {@code NotificationService.publish}——通知落库统一由 NotifyEventConsumer 消费 bs-notify-event 完成
 * （营销侧不同步调单体，单体停 → 营销照跑）。
 * <p>
 * 两类通知统一 {@code scope = BOTTOM_SYSTEM}（底部消息页 system tab，不进市集消息页）、
 * {@code ref_type = MARKETING}、type 取营销枚举（不复用 ORDER_EVENT）。
 * 幂等 = {@code notification.dedup_key}（营销票 04 定的 {@code MARKETING:...} 形状，原样随事件携带）：
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
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(message.getUserId())
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.LOTTERY_WON.name())
                .title("抽奖中奖")
                .content(content)
                .refType(REF_TYPE_MARKETING)
                .refId(message.getRecordId())
                .dedupKey(marketingDedupKey("DRAWN", message.getRecordId()))
                .build());
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
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(message.getUserId())
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.POINTS_CHANGED.name())
                .title("积分变动")
                .content(content)
                .refType(REF_TYPE_MARKETING)
                .refId(message.getRefId())
                .dedupKey(marketingDedupKey("POINTS", message.getReasonType(), message.getRefId()))
                .build());
        log.info("[MarketingNoticeCase] 积分变动通知已落 userId={} delta={} reasonType={} refId={}",
                message.getUserId(), delta, message.getReasonType(), message.getRefId());
    }

    /**
     * 对账告警（营销对账轮 drawReconScan 发现 CONFIRMED 差错 / DEAD 孤儿单时点名）：
     * 维护者告警不是用户通知——收件人落系统助手账号（通知中心 system tab 可查历史），
     * 正文优先用生产方渲染好的告警文案。dedupKey 含轮次 + 检查类型 + 单号，
     * job 重跑 / broker 重投的重复点名只落一条。
     */
    public void notifyReconAlert(DrawReconAlertMessage message) {
        String content = message.getAlertText() != null ? message.getAlertText() : reconAlertContent(message);
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(Constants.SYSTEM_USER_ID)
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.RECON_ALERT.name())
                .title("库存对账告警")
                .content(content)
                .refType(REF_TYPE_MARKETING)
                .refId(message.getBizId())
                .dedupKey(marketingDedupKey("RECON", message.getRoundNo(), message.getCheckType(), message.getBizId()))
                .build());
        log.info("[MarketingNoticeCase] 对账告警已落 roundNo={} checkType={} scopeKey={} bizId={}",
                message.getRoundNo(), message.getCheckType(), message.getScopeKey(), message.getBizId());
    }

    /** 缺省文案：事件体没带告警文案时按其余字段拼（守恒公式形状：期望 X，实际 Y） */
    private String reconAlertContent(DrawReconAlertMessage message) {
        StringBuilder content = new StringBuilder("对账轮 ").append(message.getRoundNo())
                .append("：").append(message.getCheckType()).append(" 差异");
        if (message.getScopeKey() != null) {
            content.append("（").append(message.getScopeKey()).append("）");
        }
        content.append("，定位 ").append(message.getBizId());
        if (message.getExpectSummary() != null || message.getActualSummary() != null) {
            content.append("：期望 ").append(message.getExpectSummary())
                    .append("，实际 ").append(message.getActualSummary());
        }
        return content.toString();
    }

    private String marketingDedupKey(Object... parts) {
        StringBuilder key = new StringBuilder("MARKETING");
        for (Object part : parts) {
            key.append(':').append(part);
        }
        return key.toString();
    }
}
