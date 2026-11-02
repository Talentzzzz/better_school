package com.betterschool.usecase.moderation;

import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.community.model.entity.PostCommentEntity;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.service.comment.ICommunityCommentService;
import com.betterschool.domain.community.service.post.ICommunityPostService;
import com.betterschool.domain.errand.model.valobj.ErrandReviewResult;
import com.betterschool.domain.errand.service.IErrandOrderService;
import com.betterschool.domain.moderation.model.entity.ReviewQueueItemEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskEntity;
import com.betterschool.domain.moderation.model.valobj.ReviewActionVO;
import com.betterschool.domain.moderation.model.valobj.ReviewSourceVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.domain.trade.service.product.IProductService;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.types.enums.RejectReasonCode;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 统一人工裁决用例 decide(targetType, targetId, approve, reason, operatorId)：
 * 每步：① 原域方法状态流转（条件 UPDATE + 终态幂等）② 跨域资金动作（跑腿拒绝解冻退款，流水 uk 幂等）
 * ③ 审核动作记录（INSERT IGNORE uk+轮次 幂等，source 恒 MANUAL）④ AUDIT_RESULT 通知作者（帖子/跑腿/商品通过+拒绝都通知，评论仅拒绝通知）。
 * 幂等重试语义：原域方法 0 行时终态判定静默成功，recordReview 按业务行轮次只插一行，退款不重复，通知重复可接受（衍生数据）
 */
@Service
@RequiredArgsConstructor
public class ReviewCase {

    /** content_review.reason varchar(200) */
    private static final int REASON_MAX_LENGTH = 200;

    private final IReviewService reviewService;
    private final ICommunityPostService communityPostService;
    private final ICommunityCommentService communityCommentService;
    private final IErrandOrderService errandOrderService;
    private final IProductService productService;
    private final IWalletAccountService walletAccountService;
    private final INotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 统一裁决入口（source=MANUAL）：approve=通过（对外可见），approve=false=拒绝（打回 + 跑腿解冻退款）。
     * 拒绝理由走全站统一枚举（reasonCode 必填且合法，supplement 可选），落库统一"枚举文案：补充说明"格式；
     * 通过时理由可空。operatorId 仅作裁决记录，用例本身不校验调用者角色（HTTP 层拦截）。
     * 整体单事务（spec §5.2：①业务行已切 + ②裁决已记 + ③工单已关三者原子——内部各域 TransactionTemplate
     * 均为 REQUIRED 传播，加入本外层事务；任一步失败整体回滚，无「已裁决但工单未关」的中间态）；
     * 幂等重试语义：原域方法 0 行时终态判定静默成功，recordReview 按业务行轮次只插一行，退款不重复，通知重复可接受（衍生数据）
     */
    public void decide(String targetType, String targetId, boolean approve, String reasonCode, String supplement,
                       String operatorId) {
        ReviewTargetTypeVO targetTypeVO = parseReviewableType(targetType);
        transactionTemplate.executeWithoutResult(status -> {
            dispatch(targetTypeVO, targetId, approve, reasonCode, supplement, operatorId);
            // 工单出池（spec §5.2：裁决完成即出池，与裁决同事务）：该目标全部在途工单停 DONE（重放 0 行无害）
            reviewService.closeTask(targetTypeVO, targetId);
        });
    }

    /**
     * 工单裁决（spec §5.2「裁决写入——强绑定」）：裁决只能由持有人对自己手上的工单做出。
     * ① 断言当前工单确实由本人持有（在池 claim_by 为空 / 他人持有 / 已被回收清空持有人 → 403
     * 「请先领单 / 工单已被回收」，业务行零改动）；② 分发既有 approve()/reject() 唯一内核
     * （不复制、不旁路：域状态流转条件 UPDATE + content_review 记录 + 跨域资金动作 + 通知）；
     * ③ 同事务以「持有人 + 租约中」双守卫把工单置 DONE 并写裁决时刻——业务行已切 + 裁决已记 + 工单已关三者原子。
     * <p>
     * 幂等（重试语义，同既有 handlePayTimeout 口径）：已 DONE 的工单重放时关单命中 0 行但持有人快照仍是本人
     * → 判定为本人已裁决的重放 → 继续走幂等内核（域方法命中 0 行、审核记录撞 uk 跳过、退款流水 uk 防重），
     * 整体幂等成功、零副作用；若关单 0 行时工单已不是本人的（租约恰在本事务前被回收）→ 抛错整体回滚，业务行零改动
     */
    public void decideTask(String taskId, boolean approve, String reasonCode, String supplement, String operatorId) {
        ReviewTaskEntity task = reviewService.findTask(taskId);
        if (task == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "审核工单不存在");
        }
        // ① 强绑定断言：非持有人（在池/他人持有/已被回收）直接 403，不触发任何业务写
        if (task.getClaimBy() == null || !task.getClaimBy().equals(operatorId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "请先领单 / 工单已被回收");
        }
        transactionTemplate.executeWithoutResult(status -> {
            // ③ 关单先行（与 ② 同一事务，顺序不影响提交原子性，但把回收竞态挡在业务行翻转之前）：
            //    0 行 = 已离开「本人租约中」——已 DONE（本人已裁决的重放，幂等放行）或已被回收（抛错回滚）
            if (!reviewService.closeHeldTask(taskId, operatorId) && !decidedBySelf(taskId, operatorId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "工单已被回收，请刷新后重新领单");
            }
            // ② 分发到既有裁决内核（唯一内核不复制、不旁路）
            dispatch(task.getTargetType(), task.getTargetId(), approve, reasonCode, supplement, operatorId);
        });
    }

    /** 重放判定：工单已 DONE 且持有人快照仍是本人 = 本人已裁决后的重试（关单 0 行属幂等，不报错） */
    private boolean decidedBySelf(String taskId, String operatorId) {
        ReviewTaskEntity current = reviewService.findTask(taskId);
        return current != null && "DONE".equals(current.getClaimState())
                && operatorId.equals(current.getClaimBy());
    }

    /**
     * 既有管理端人工审核入口（遗留收口，spec §5.2）：**不保留绕过路径**——不再直接裁决，改为按
     * 「类型 + 内容 id + 当前登录用户」反查其持有的在途工单（claim_by=我 且 claim_state='CLAIMED'），
     * 命中则转 {@link #decideTask}（强绑定），未命中 → 403「请先领单」，杜绝「不领单直接裁」
     */
    public void review(String targetType, String targetId, ReviewActionVO action, String operatorId,
                       String reasonCode, String supplement) {
        ReviewTargetTypeVO targetTypeVO = parseReviewableType(targetType);
        ReviewTaskEntity held = reviewService.findHeldTask(targetTypeVO, targetId, operatorId);
        if (held == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "请先领单");
        }
        decideTask(held.getTaskId(), action == ReviewActionVO.APPROVE, reasonCode, supplement, operatorId);
    }

    /** 裁决分发：approve/reject 各按类型走既有分支（域状态流转 + 审核记录 + 跨域资金动作 + 通知），供两个入口共用 */
    private void dispatch(ReviewTargetTypeVO targetType, String targetId, boolean approve, String reasonCode,
                          String supplement, String operatorId) {
        if (approve) {
            approve(targetType, targetId, operatorId);
        } else {
            reject(targetType, targetId, operatorId, reasonCode, formatRejectReason(reasonCode, supplement));
        }
    }

    private static ReviewTargetTypeVO parseReviewableType(String targetType) {
        ReviewTargetTypeVO targetTypeVO = ReviewTargetTypeVO.from(targetType);
        if (targetTypeVO == null || targetTypeVO == ReviewTargetTypeVO.USER) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "审核对象类型不合法");
        }
        return targetTypeVO;
    }

    private void approve(ReviewTargetTypeVO targetType, String targetId, String operatorId) {
        ReviewQueueItemEntity detail = reviewService.reviewDetail(targetType, targetId);
        switch (targetType) {
            case POST -> {
                communityPostService.approvePost(targetId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.APPROVE,
                        ReviewSourceVO.MANUAL, operatorId, null, null);
                notificationService.sendToUser(detail.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_RESULT, "审核结果",
                        "您的帖子《" + summary(detail.getContent()) + "》已通过审核，现已公开", "POST", targetId);
            }
            case COMMENT -> {
                boolean newlyApproved = communityCommentService.approveComment(targetId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.APPROVE,
                        ReviewSourceVO.MANUAL, operatorId, null, null);
                // 评论通过才发互动通知（帖被评/评被回）；仅首次通过触发，重复裁决不重复发（ADR-0004）
                if (newlyApproved) {
                    notifyCommentInteractions(communityCommentService.getComment(targetId));
                }
            }
            case ERRAND -> {
                // 业务 id 是 UUID 字符串，targetId 直传（uuid-biz-id 票 05）
                errandOrderService.reviewApproveOrder(targetId, operatorId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.APPROVE,
                        ReviewSourceVO.MANUAL, operatorId, null, null);
                notificationService.sendToUser(detail.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_RESULT, "审核结果",
                        "您的跑腿订单已通过审核，现已对外可见", "ERRAND", targetId);
            }
            case PRODUCT -> {
                // 商品先审后售无资金环节：通过转 ON_SALE 对外可见（转在售时落搜索同步消息）
                productService.reviewApproveProduct(targetId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.APPROVE,
                        ReviewSourceVO.MANUAL, operatorId, null, null);
                notificationService.sendToUser(detail.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_RESULT, "审核结果",
                        "您的商品《" + summary(detail.getContent()) + "》已通过审核，现已公开", "PRODUCT", targetId);
            }
            default -> throw new BusinessException(ResponseCode.PARAM_ERROR, "审核对象类型不合法");
        }
    }

    private void reject(ReviewTargetTypeVO targetType, String targetId, String operatorId,
                        String reasonCode, String reason) {
        ReviewQueueItemEntity detail = reviewService.reviewDetail(targetType, targetId);
        switch (targetType) {
            case POST -> {
                communityPostService.rejectPost(targetId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.REJECT,
                        ReviewSourceVO.MANUAL, operatorId, reason, reasonCode);
                notificationService.sendToUser(detail.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_RESULT, "审核结果",
                        "您的帖子《" + summary(detail.getContent()) + "》未通过审核：" + reason, "POST", targetId);
            }
            case COMMENT -> {
                communityCommentService.rejectComment(targetId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.REJECT,
                        ReviewSourceVO.MANUAL, operatorId, reason, reasonCode);
                notificationService.sendToUser(detail.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_RESULT, "审核结果",
                        "您的评论未通过审核：" + reason, "COMMENT", targetId);
            }
            case ERRAND -> {
                // 先翻转状态（幂等闸门），再解冻退款（流水 uk 防重）；业务 id 是 UUID 字符串直传（uuid-biz-id 票 05）
                String errandOrderId = targetId;
                ErrandReviewResult refundDemand = errandOrderService.reviewRejectOrder(errandOrderId, operatorId, reason);
                walletAccountService.unfreeze(refundDemand.getPublisherId(), refundDemand.getReward(), errandOrderId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.REJECT,
                        ReviewSourceVO.MANUAL, operatorId, reason, reasonCode);
                notificationService.sendToUser(detail.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_RESULT, "审核结果",
                        "您的跑腿订单未通过审核，冻结金额已全额退回：" + reason, "ERRAND", targetId);
            }
            case PRODUCT -> {
                // 商品拒绝无资金环节，拒绝即 REJECTED（无退款）
                productService.reviewRejectProduct(targetId);
                reviewService.recordReview(targetType, targetId, detail.getAuthorId(), detail.getReviewRound(), ReviewActionVO.REJECT,
                        ReviewSourceVO.MANUAL, operatorId, reason, reasonCode);
                notificationService.sendToUser(detail.getAuthorId(), NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.AUDIT_RESULT, "审核结果",
                        "您的商品未通过审核：" + reason, "PRODUCT", targetId);
            }
            default -> throw new BusinessException(ResponseCode.PARAM_ERROR, "审核对象类型不合法");
        }
    }

    /**
     * 拒绝理由收口全站统一枚举：reasonCode 必填且合法（作者要能看到具体不通过原因，不给默认文案兜底）；
     * 落库格式"枚举文案：补充说明"，受 content_review.reason varchar(200) 约束，补充说明超长截断（不切断代理对）。
     * 全站唯一拒绝理由格式（人工审核工作台与统一裁决入口共用）
     */
    static String formatRejectReason(String reasonCode, String supplement) {
        RejectReasonCode code = RejectReasonCode.from(reasonCode);
        if (code == null) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "拒绝理由必填且为合法枚举 code");
        }
        if (supplement == null || supplement.isBlank()) {
            return code.getLabel();
        }
        String trimmed = supplement.trim();
        int maxSupplement = REASON_MAX_LENGTH - code.getLabel().length() - 1;
        if (trimmed.length() > maxSupplement) {
            trimmed = trimmed.substring(0, maxSupplement);
            if (Character.isHighSurrogate(trimmed.charAt(trimmed.length() - 1))) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
        }
        return code.getLabel() + "：" + trimmed;
    }

    /** 评论互动通知（审核通过后才发，ADR-0004）：帖被评通知帖主、评被回通知 parent 评论作者；自己评论/回复自己不发 */
    private void notifyCommentInteractions(PostCommentEntity comment) {
        if (comment == null) {
            return;
        }
        String commentAuthorId = comment.getUserId();
        PostEntity post = communityPostService.getPost(comment.getPostId());
        if (post != null && !post.getUserId().equals(commentAuthorId)) {
            notificationService.sendToUser(post.getUserId(), NotificationScopeVO.INTERACTION_COMMENT, NotificationTypeVO.POST_COMMENTED, "互动通知",
                    "你的帖子收到了新评论", "POST", comment.getPostId());
        }
        if (!PostCommentEntity.NO_REFERENCE.equals(comment.getParentId())) {
            PostCommentEntity parent = communityCommentService.getComment(comment.getParentId());
            if (parent != null && !parent.getUserId().equals(commentAuthorId)) {
                notificationService.sendToUser(parent.getUserId(), NotificationScopeVO.INTERACTION_COMMENT, NotificationTypeVO.COMMENT_REPLIED, "互动通知",
                        "你的评论收到了新回复", "COMMENT", comment.getParentId());
            }
        }
    }

    /** 通知展示用内容摘要（≤20 字） */
    static String summary(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 20 ? content.substring(0, 20) + "…" : content;
    }
}
