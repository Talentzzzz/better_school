package com.betterschool.usecase.community;

import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.PostReviewTimeoutVO;
import com.betterschool.domain.community.service.post.ICommunityPostService;
import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.moderation.service.IModerationScreenService;
import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import com.betterschool.types.concurrent.VirtualThreadFanout;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 发帖用例编排：信用分门槛前置校验 + 同步初筛（命中报错不落库，与商品/跑腿语义一致） + community 域发布（落库即待审）。
 * 草稿同轨：保存/编辑不做初筛（私有内容），提交与直接发布同门槛校验，初筛命中停留草稿
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostPublishCase {

    private final ICommunityPostService communityPostService;
    private final ICreditAccountService creditAccountService;
    private final IModerationScreenService moderationScreenService;
    private final IReviewService reviewService;
    private final INotificationService notificationService;
    private final IUserRepository userRepository;
    private final VirtualThreadFanout fanout;

    /** 被拒帖子的拒绝理由（content_review.reason），供详情对作者带出 */
    public String postRejectReason(String postId) {
        return reviewService.rejectReason(ReviewTargetTypeVO.POST, postId);
    }

    /** 批量被拒拒绝理由（postId → 最新理由），供"我的"列表对 REJECTED 行带出（免 N+1） */
    public Map<String, String> postRejectReasons(List<String> postIds) {
        return reviewService.rejectReasons(ReviewTargetTypeVO.POST, postIds);
    }

    public String publishPost(String userId, String title, String content,
                              List<String> images, String cover) {
        creditAccountService.ensureOperable(userId);
        // 同步初筛：命中即发布失败、不落库，用户修改后重发
        screenText(title, content);
        return communityPostService.publishPost(userId, title, content, images, cover);
    }

    /** 保存帖子草稿：不做初筛、不做信用分门槛，字段可缺省 */
    public String savePostDraft(String userId, String title, String content, List<String> images,
                                String cover) {
        return communityPostService.savePostDraft(userId, title, content, images, cover);
    }

    /** 编辑帖子草稿：仅 DRAFT 可编辑（待审中需先撤回） */
    public void updatePostDraft(String userId, String postId, String title, String content,
                                List<String> images, String cover) {
        communityPostService.updatePostDraft(userId, postId, title, content, images, cover);
    }

    /** 提交帖子草稿：与直接发布同门槛校验，初筛命中停留草稿，通过进入待审 */
    public void submitPostDraft(String userId, String postId) {
        PostEntity draft = communityPostService.getOwnedPostDraft(userId, postId);
        creditAccountService.ensureOperable(userId);
        validatePostDraftComplete(draft);
        // 同步初筛：命中即提交失败，草稿原样保留可继续修改
        screenText(draft.getTitle(), draft.getContent());
        communityPostService.submitPostDraft(userId, postId);
    }

    /** 同步初筛（与直接发布同口径）：命中即业务失败，发布不落库、提交停留草稿 */
    private void screenText(String title, String content) {
        if (moderationScreenService.screen((title == null ? "" : title) + content)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "内容包含违规词汇，请修改后重试");
        }
    }

    /**
     * 待审超 24h 提醒扫描（帖子）：收件人 ADMIN+AUDITOR，同一帖子同一轮次每收件人只提醒一次
     * （dedup_key 含审核轮次，先例商品/跑腿的 key 不含轮次——帖子撤回/被拒可重提，重提后新一轮须可再次提醒）；
     * 逐收件人吞异常不影响扫描主流程，下轮扫描自动补发；只提醒，不自动通过也不自动拒绝
     */
    public void remindPostReviewTimeouts() {
        List<String> reviewerIds = List.of(
                userRepository.listUserIdsByRole(UserRoleVO.ADMIN),
                userRepository.listUserIdsByRole(UserRoleVO.AUDITOR))
                .stream().flatMap(List::stream).distinct().toList();
        // 扇出项 = 一条待审帖（其下逐收件人仍是串行循环，项内自行吞异常）
        fanout.forEach("post-remind-", communityPostService.listReviewTimeoutPosts(),
                timeoutPost -> remindReviewers(reviewerIds, timeoutPost));
    }

    private void remindReviewers(List<String> reviewerIds, PostReviewTimeoutVO timeoutPost) {
        for (String reviewerId : reviewerIds) {
            try {
                notificationService.sendSystem(reviewerId, NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.ORDER_EVENT, "审核提醒",
                        "帖子 " + timeoutPost.getPostId() + " 已待审超过 24 小时，请及时处理",
                        ReviewTargetTypeVO.POST.name(), timeoutPost.getPostId(),
                        NotificationTypeVO.ORDER_EVENT.name() + ":REVIEW_TIMEOUT:POST:" + timeoutPost.getPostId()
                                + ":" + timeoutPost.getReviewRound() + ":" + reviewerId);
            } catch (Exception e) {
                log.warn("[PostPublishCase] 待审超时提醒发送失败（下轮扫描补发） postId={} reviewerId={}",
                        timeoutPost.getPostId(), reviewerId, e);
            }
        }
    }

    /** 草稿用例层完整性校验：正文必填，标题/配图/封面可空 */
    private void validatePostDraftComplete(PostEntity draft) {
        if (draft.getContent() == null || draft.getContent().isBlank()) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿内容不完整，请补全正文后再提交");
        }
    }
}
