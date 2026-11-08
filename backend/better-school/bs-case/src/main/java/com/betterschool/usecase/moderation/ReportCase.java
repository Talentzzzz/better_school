package com.betterschool.usecase.moderation;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.chat.service.recall.IChatRecallService;
import com.betterschool.domain.community.service.comment.ICommunityCommentService;
import com.betterschool.domain.community.service.post.ICommunityPostService;
import com.betterschool.domain.credit.model.valobj.CreditReasonTypeVO;
import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.moderation.model.valobj.ReportProcessResult;
import com.betterschool.domain.moderation.model.valobj.ReportStatusVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.moderation.service.IReportService;
import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 举报用例编排。
 * 提交：登录用户可举报帖子/评论/用户/消息（跑腿订单不纳入举报，只走审核队列）；
 * 处理（审核员）：帖子/评论/用户 → 下架 + 扣 10 分（REPORT_CONFIRMED，uk_user_reason_ref 按 report_id 幂等，一内容一罚）+ 双方通知；
 * MESSAGE → chat 强制撤回（recalled_by=AUDITOR，无窗口，已撤回幂等），不走扣分流水；
 * 不实 → 忽略 + 通知举报人。幂等重试：processReport 条件 UPDATE 0 行仍返回准确需求，不丢扣分
 */
@Service
@RequiredArgsConstructor
public class ReportCase {

    private final IReportService reportService;
    private final IReviewService reviewService;
    private final ICommunityPostService communityPostService;
    private final ICommunityCommentService communityCommentService;
    private final ICreditAccountService creditAccountService;
    private final INotificationService notificationService;
    private final IChatRepository chatRepository;
    private final IChatRecallService chatRecallService;

    /**
     * targetId 是多态举报对象 id，入口统一 String：USER/POST/COMMENT/MESSAGE 的业务 ID 都是 UUID 字符串
     * （POST/COMMENT 票 03、MESSAGE 票 04 翻转后全链 String，无需任何数值解析）
     */
    public String submitReport(String reporterId, String targetType, String targetId, String reason) {
        ReviewTargetTypeVO targetTypeVO = ReviewTargetTypeVO.from(targetType);
        if (targetTypeVO == null) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "举报对象类型不合法");
        }
        if (targetTypeVO == ReviewTargetTypeVO.ERRAND) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "跑腿订单暂不支持举报");
        }
        if (targetTypeVO == ReviewTargetTypeVO.MESSAGE) {
            return submitMessageReport(reporterId, targetId, reason);
        }
        if (targetTypeVO == ReviewTargetTypeVO.USER) {
            if (!reviewService.existsUser(targetId)) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "用户不存在");
            }
            if (reporterId.equals(targetId)) {
                throw new BusinessException(ResponseCode.PARAM_ERROR, "不能举报自己");
            }
            return reportService.submitReport(reporterId, targetTypeVO, targetId, targetId, reason, null);
        }
        // 帖子/评论：moderation 只读直查拿作者与内容快照（举报证据，内容后续可能被删）
        var detail = reviewService.reviewDetail(targetTypeVO, targetId);
        if (reporterId.equals(detail.getAuthorId())) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "不能举报自己发布的内容");
        }
        return reportService.submitReport(reporterId, targetTypeVO, targetId, detail.getAuthorId(), reason,
                snapshot(detail.getContent()));
    }

    public void processReport(String reportId, String action, String handlerId) {
        ReportStatusVO targetStatus = ReportStatusVO.from(action);
        if (targetStatus == ReportStatusVO.PENDING) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "处理动作不合法（PROCESSED/IGNORED）");
        }
        ReportProcessResult result = reportService.processReport(reportId, targetStatus, handlerId);
        if (targetStatus == ReportStatusVO.IGNORED) {
            notificationService.sendToUser(result.getReporterId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.REPORT_RESULT, "举报处理结果",
                    "您提交的举报经核实不成立，已忽略", "REPORT", reportId);
            return;
        }
        // chat 消息核实违规 = 强制撤回（无窗口，已撤回幂等跳过）；chat 无下架概念，不走扣分流水
        if (result.getTargetType() == ReviewTargetTypeVO.MESSAGE) {
            chatRecallService.forceRecallByAuditor(result.getTargetId());
            notificationService.sendToUser(result.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.REPORT_RESULT, "消息处理通知",
                    "您发送的消息因被举报核实违规，已撤回", "REPORT", reportId);
            notificationService.sendToUser(result.getReporterId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.REPORT_RESULT, "举报处理结果",
                    "您提交的举报已核实，该消息已撤回", "REPORT", reportId);
            return;
        }
        // 核实违规：内容下架（帖子/评论）；用户举报无下架动作（扣分 + 低分联动封禁兜底）
        if (result.getTargetType() == ReviewTargetTypeVO.POST) {
            communityPostService.forceDeletePost(result.getTargetId());
        } else if (result.getTargetType() == ReviewTargetTypeVO.COMMENT) {
            communityCommentService.markDeleted(result.getTargetId());
        }
        if (result.isNeedDeduct()) {
            // 流水先行幂等：uk_user_reason_ref(user_id, REPORT_CONFIRMED, report_id) 冲突 = 已扣过；一内容一罚由 needDeduct 判定
            // report_id 已是 UUID 字符串业务键（uuid-biz-id 票 06），直传不转形
            creditAccountService.applyChange(result.getAuthorId(), CreditReasonTypeVO.REPORT_CONFIRMED, reportId);
        }
        notificationService.sendToUser(result.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.REPORT_RESULT, "内容处理通知",
                "您发布的内容因被举报核实违规，已下架并扣除 10 信用分", "REPORT", reportId);
        notificationService.sendToUser(result.getReporterId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.REPORT_RESULT, "举报处理结果",
                "您提交的举报已核实，内容已下架", "REPORT", reportId);
    }

    /** 消息举报：举报者须为会话成员（含被移除成员，与历史可读口径一致）、不能举报自己、SYSTEM 无作者不可举报；已撤回消息可举报（追责留痕）；messageId 是 msg_id 业务键（UUID 字符串） */
    private String submitMessageReport(String reporterId, String messageId, String reason) {
        MessageEntity messageEntity = chatRepository.findMessageById(messageId);
        if (messageEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "消息不存在");
        }
        if (messageEntity.getFromUserId() == null) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "系统消息不支持举报");
        }
        if (reporterId.equals(messageEntity.getFromUserId())) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "不能举报自己的消息");
        }
        UserRoomEntity membershipEntity = chatRepository.findUserRoom(messageEntity.getRoomId(), reporterId);
        if (membershipEntity == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "仅会话成员可举报消息");
        }
        return reportService.submitReport(reporterId, ReviewTargetTypeVO.MESSAGE, messageId,
                messageEntity.getFromUserId(), reason, messageSnapshot(messageEntity));
    }

    /** 消息举报快照（≤500 字）：TEXT/IMAGE 存载荷原文，CARD 存 ref 标识（如 CARD:PRODUCT#123） */
    private String messageSnapshot(MessageEntity messageEntity) {
        if (messageEntity.getMsgType() == MsgTypeVO.CARD) {
            return "CARD:" + messageEntity.getRefType() + "#" + messageEntity.getRefId();
        }
        return snapshot(messageEntity.getContent());
    }

    /** 举报证据快照（≤500 字） */
    private String snapshot(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > 500 ? content.substring(0, 500) + "…" : content;
    }
}
