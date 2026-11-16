package com.betterschool.usecase.credit;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.credit.model.entity.CreditDetailEntity;
import com.betterschool.domain.credit.model.valobj.CreditChangeResult;
import com.betterschool.domain.credit.service.ICreditAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 信用分用例编排：admin 操作顺序调用 credit 域（域内事务）+ 下发变动通知；
 * credit 域动作与通知各自独立，通知为衍生数据（失败重试可能重复，接受）
 */
@Service
@RequiredArgsConstructor
public class CreditCase {

    private final ICreditAccountService creditAccountService;
    private final INotificationService notificationService;

    public int getCreditScore(String userId) {
        return creditAccountService.getCreditScore(userId);
    }

    public String getStatus(String userId) {
        return creditAccountService.getStatus(userId);
    }

    public List<CreditDetailEntity> listDetails(String userId, int page, int pageSize) {
        return creditAccountService.listDetails(userId, page, pageSize);
    }

    public long countDetails(String userId) {
        return creditAccountService.countDetails(userId);
    }

    public void adminAdjust(String targetUserId, int delta, String reason) {
        CreditChangeResult changeResult = creditAccountService.adjustByAdmin(targetUserId, delta, reason);
        if (changeResult == null) {
            return;
        }
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(targetUserId)
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.CREDIT_CHANGE.name())
                .title("信用分变动")
                .content("管理员调分 " + (delta > 0 ? "+" : "") + delta + " 分，当前信用分 " + changeResult.getCurrentScore()
                        + "（" + reason + "）")
                .refType("CREDIT")
                .refId(null)
                .dedupKey(null)
                .build());
    }

    public void adminBan(String targetUserId, String reason) {
        creditAccountService.banByAdmin(targetUserId);
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(targetUserId)
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.CREDIT_CHANGE.name())
                .title("账号封禁")
                .content("账号已被封禁，无法发布或接单" + (reason == null || reason.isBlank() ? "" : "（原因：" + reason + "）"))
                .refType("CREDIT")
                .refId(null)
                .dedupKey(null)
                .build());
    }

    public void adminUnban(String targetUserId) {
        creditAccountService.unbanByAdmin(targetUserId);
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(targetUserId)
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.CREDIT_CHANGE.name())
                .title("账号解封")
                .content("账号已解封，当前信用分 " + creditAccountService.getCreditScore(targetUserId) + "，可正常发布或接单")
                .refType("CREDIT")
                .refId(null)
                .dedupKey(null)
                .build());
    }
}
