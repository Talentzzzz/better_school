package com.betterschool.usecase.post;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.domain.post.model.entity.PostCommentEntity;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.entity.PostRebateClaimTaskEntity;
import com.betterschool.domain.post.model.valobj.PostPublishResultVO;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.domain.draw.service.drawcount.IDrawCountAccountService;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.service.profile.IUserProfileService;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.model.valobj.RebateTypeEnum;
import com.betterschool.domain.rebate.service.IRebateService;
import com.betterschool.types.common.CommentLikedMessage;
import com.betterschool.types.common.PostLikedMessage;
import com.betterschool.types.common.PostRebateCreditMessage;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.rebate.RebateActionTypeEnum;
import com.betterschool.types.rebate.RebateClaimStateEnum;
import com.betterschool.types.rebate.RebatePeriodKeys;
import com.betterschool.types.rebate.RebateRefTypeEnum;
import com.betterschool.usecase.notification.INotificationCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 帖子用例实现：发帖 / 草稿 / 评论 / 点赞 / 发帖返利补偿，跨域编排集中在这一个 case。
 * <p>
 * 发帖：信用分门槛前置校验 + post 域发布（落库即可见）+ 同步领取发帖返利；草稿同轨——
 * 保存/编辑私有内容不做校验，提交与直接发布同门槛校验。
 * <p>
 * 返利两段式：发布事务提交后，请求线程同步向 rebate 域领取（见 publishPost / submitPostDraft），
 * 领取结果（CLAIMED / DENIED / NO_REBATE / CLAIM_ERROR）随发布响应返回；积分 / 抽奖次数入账由领取事务内
 * 按订单行落下的 credit outbox 异步完成（{@link #credit}），失败交 broker 重试。领取阶段异常不影响
 * 已提交的发布——响应 CLAIM_ERROR，领取任务留 PENDING 由 {@code postRebateClaimScan} 进程内重放（{@link #replayClaim}）。
 * <p>
 * 点赞：post 域点赞事务（唯一键幂等 + like_count 同事务 ±1）先提交，再落「谁赞了谁」的事实事件
 * outbox（自赞在发 MQ 前拦截；事件是附属动作，落库/发送失败不回滚点赞）。消费端把事实翻成互动通知，
 * 直接幂等落 notification 并原地投递。按（sender + 目标）去重：同一人赞→取消→再赞只发一条；
 * 取消不撤回已发通知。
 * <p>
 * 跨域不开事务（ADR-0016）：各域方法自身事务原子，失败靠唯一键幂等 + broker 重试收敛。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostCase implements IPostCase {

    private final IPostService postService;
    private final ICreditAccountService creditAccountService;
    private final IRebateService rebateService;
    private final IPointsAccountService pointsAccountService;
    private final IDrawCountAccountService drawCountAccountService;
    private final INotificationCase notificationCase;
    private final IUserProfileService userProfileService;

    // ---------- 发帖 / 草稿 ----------

    @Override
    public RebateClaimResultVO publishPost(String userId, String title, String content,
                                           List<String> images, String cover) {
        creditAccountService.ensureOperable(userId);
        PostPublishResultVO postPublishResultVO = postService.publishPost(userId, title, content, images, cover);
        String postId = postPublishResultVO.getPostId();
        RebateClaimResultVO rebateClaimResultVO = rebateService.claimActionRebateOrPending(
                RebateActionTypeEnum.POST.getCode(), userId, RebateRefTypeEnum.POST.getCode(), postId,
                RebatePeriodKeys.of(postPublishResultVO.getPublishTime()));
        // CLAIM_ERROR = 同步领取抛异常、结果未知，任务保持 PENDING 交 postRebateClaimScan 重放，不能置 DONE
        if (rebateClaimResultVO.getRebateClaimState() != RebateClaimStateEnum.CLAIM_ERROR) {
            postService.markRebateClaimTaskDone(postId);
        }
        return rebateClaimResultVO;
    }

    @Override
    public String savePostDraft(String userId, String title, String content, List<String> images,
                                String cover) {
        return postService.savePostDraft(userId, title, content, images, cover);
    }

    @Override
    public void updatePostDraft(String userId, String postId, String title, String content,
                                List<String> images, String cover) {
        postService.updatePostDraft(userId, postId, title, content, images, cover);
    }

    @Override
    public RebateClaimResultVO submitPostDraft(String userId, String postId) {
        creditAccountService.ensureOperable(userId);
        PostPublishResultVO postPublishResultVO = postService.submitPostDraft(userId, postId);
        String submittedPostId = postPublishResultVO.getPostId();
        RebateClaimResultVO rebateClaimResultVO = rebateService.claimActionRebateOrPending(
                RebateActionTypeEnum.POST.getCode(), userId, RebateRefTypeEnum.POST.getCode(), submittedPostId,
                RebatePeriodKeys.of(postPublishResultVO.getPublishTime()));
        if (rebateClaimResultVO.getRebateClaimState() != RebateClaimStateEnum.CLAIM_ERROR) {
            postService.markRebateClaimTaskDone(submittedPostId);
        }
        return rebateClaimResultVO;
    }

    // ---------- 点赞 ----------

    @Override
    public void likePost(String postId, String userId) {
        postService.likePost(postId, userId);
    }

    @Override
    public void notifyPostLiked(PostLikedMessage postLikedMessage) {
        // 自赞已由生产端（PostServiceImpl#likePost）在发 MQ 前拦截；这里兜底在途/历史消息
        if (postLikedMessage.getAuthorId() == null
                || postLikedMessage.getAuthorId().equals(postLikedMessage.getLikerUserId())) {
            return;
        }
        // 直接走通知用例：幂等落 notification 后原地投递，不再发第二条 MQ。
        // notificationId 由 dedupKey 确定性生成——broker 重投同一条事实时是同一个通知 id，
        // 落库幂等 + 「落库成功但投递失败」的补投都靠它收敛
        String dedupKey = NotificationTypeEnum.POST_LIKED.name() + ":" + postLikedMessage.getPostId()
                + ":" + postLikedMessage.getLikerUserId();
        notificationCase.consume(NotificationEventMessage.builder()
                .notificationId(UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8)).toString())
                .userId(postLikedMessage.getAuthorId())
                .senderId(postLikedMessage.getLikerUserId())
                .scope(NotificationScopeEnum.INTERACTION_LIKE.name())
                .type(NotificationTypeEnum.POST_LIKED.name())
                .title("互动通知")
                .content("你的帖子收到了一个赞")
                .refType("POST")
                .refId(postLikedMessage.getPostId())
                .dedupKey(dedupKey)
                .build());
    }

    @Override
    public void likeComment(String commentId, String userId) {
        postService.likeComment(commentId, userId);
    }

    @Override
    public void notifyCommentLiked(CommentLikedMessage commentLikedMessage) {
        // 自赞已由生产端（PostServiceImpl#likeComment）在发 MQ 前拦截；这里兜底在途/历史消息
        if (commentLikedMessage.getAuthorId() == null
                || commentLikedMessage.getAuthorId().equals(commentLikedMessage.getLikerUserId())) {
            return;
        }
        // 与帖子点赞同一套收敛方式：notificationId 由 dedupKey 确定性生成，broker 重投仍落同一条通知
        String dedupKey = NotificationTypeEnum.COMMENT_LIKED.name() + ":" + commentLikedMessage.getCommentId()
                + ":" + commentLikedMessage.getLikerUserId();
        notificationCase.consume(NotificationEventMessage.builder()
                .notificationId(UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8)).toString())
                .userId(commentLikedMessage.getAuthorId())
                .senderId(commentLikedMessage.getLikerUserId())
                .scope(NotificationScopeEnum.INTERACTION_LIKE.name())
                .type(NotificationTypeEnum.COMMENT_LIKED.name())
                .title("互动通知")
                .content("你的评论收到了一个赞")
                .refType("COMMENT")
                .refId(commentLikedMessage.getCommentId())
                .dedupKey(dedupKey)
                .build());
    }

    @Override
    public void unlikeComment(String commentId, String userId) {
        postService.unlikeComment(commentId, userId);
    }

    // ---------- 评论发布 ----------

    @Override
    public PostCommentEntity publishRootComment(String postId, String userId, String content) {
        PostCommentEntity postCommentEntity = postService.publishRootComment(postId, userId, content);
        List<UserEntity> userEntityList = userProfileService.listBasic(List.of(userId));
        UserEntity userEntity = userEntityList.isEmpty() ? null : userEntityList.get(0);
        postCommentEntity.setAuthorNickname(userEntity == null ? null : userEntity.getNickname());
        return postCommentEntity;
    }

    @Override
    public PostCommentEntity publishReplyComment(String targetCommentId, String userId, String content) {
        PostCommentEntity postCommentEntity = postService.publishReplyComment(targetCommentId, userId, content);
        List<UserEntity> userEntityList = userProfileService.listBasic(List.of(userId));
        UserEntity userEntity = userEntityList.isEmpty() ? null : userEntityList.get(0);
        postCommentEntity.setAuthorNickname(userEntity == null ? null : userEntity.getNickname());
        return postCommentEntity;
    }

    // ---------- 跨域只读：用户可见性与昵称 ----------

    @Override
    public List<PostEntity> listUserLikedPosts(String ownerId, String viewerId, int page, int pageSize) {
        return userProfileService.isLikedVisibleTo(ownerId, viewerId)
                ? postService.listUserLikedPosts(ownerId, viewerId, page, pageSize) : List.of();
    }

    @Override
    public long countUserLikedPosts(String ownerId, String viewerId) {
        return userProfileService.isLikedVisibleTo(ownerId, viewerId)
                ? postService.countUserLikedPosts(ownerId, viewerId) : 0;
    }

    @Override
    public List<PostCommentEntity> listCommentsByUser(String ownerId, String viewerId, int page, int pageSize) {
        return userProfileService.isCommentsVisibleTo(ownerId, viewerId)
                ? postService.listCommentsByUser(ownerId, viewerId, page, pageSize) : List.of();
    }

    @Override
    public long countCommentsByUser(String ownerId, String viewerId) {
        return userProfileService.isCommentsVisibleTo(ownerId, viewerId)
                ? postService.countCommentsByUser(ownerId, viewerId) : 0;
    }

    // ---------- 发帖返利补偿（域内收敛任务，不走 MQ；入账仍走 MQ） ----------

    @Override
    public void replayClaim(PostRebateClaimTaskEntity postRebateClaimTaskEntity) {
        String periodKey = (postRebateClaimTaskEntity.getPeriodKey() == null
                || postRebateClaimTaskEntity.getPeriodKey().isBlank())
                ? RebatePeriodKeys.today()
                : postRebateClaimTaskEntity.getPeriodKey();
        rebateService.claimActionRebate(
                RebateActionTypeEnum.POST.getCode(), postRebateClaimTaskEntity.getUserId(),
                RebateRefTypeEnum.POST.getCode(), postRebateClaimTaskEntity.getPostId(), periodKey);
        postService.markRebateClaimTaskDone(postRebateClaimTaskEntity.getPostId());
    }

    @Override
    public int compensateDueRebateClaims(int limit) {
        int completed = 0;
        for (PostRebateClaimTaskEntity task : postService.listDueRebateClaimTasks(limit)) {
            // 抢占：条件更新递增 retry_count 并写退避时点；被其他实例抢先 / 状态漂移则跳过
            if (!postService.claimRebateClaimTask(task)) {
                continue;
            }
            try {
                replayClaim(task);
                completed++;
            } catch (Exception e) {
                // 逐单隔离：一行失败只处置本行（退避重试 / 耗尽置死信），不中断本轮批次
                if (task.getRetryCount() + 1 >= IPostService.REBATE_CLAIM_MAX_RETRY) {
                    postService.markRebateClaimTaskDead(task.getId());
                    log.error("[PostCase] 发帖返利领取任务重试耗尽置死信，需人工介入 postId={}",
                            task.getPostId(), e);
                } else {
                    log.warn("[PostCase] 发帖返利领取任务重放失败，留待下轮 postId={} attempt={}",
                            task.getPostId(), task.getRetryCount() + 1, e);
                }
            }
        }
        return completed;
    }

    @Override
    public void grantPostRebate(PostRebateCreditMessage postRebateCreditMessage) {
        if (postRebateCreditMessage == null || postRebateCreditMessage.getOrderId() == null
                || postRebateCreditMessage.getUserId() == null || postRebateCreditMessage.getPostId() == null
                || postRebateCreditMessage.getRebateType() == null) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "发帖返利入账消息缺少必要参数");
        }
        RebateTypeEnum rebateTypeVO = RebateTypeEnum.from(postRebateCreditMessage.getRebateType());
        // 一个订单行只入一个桶：类型决定去向，金额是领取时快照
        if (postRebateCreditMessage.getRebateValue() > 0) {
            switch (rebateTypeVO) {
                case POINTS -> pointsAccountService.addForRebate(
                        postRebateCreditMessage.getUserId(), postRebateCreditMessage.getPostId(),
                        postRebateCreditMessage.getRebateValue());
                case DRAW_COUNT -> drawCountAccountService.addForRebate(
                        postRebateCreditMessage.getUserId(), postRebateCreditMessage.getPostId(),
                        postRebateCreditMessage.getRebateValue());
            }
        }
        rebateService.markGranted(postRebateCreditMessage.getOrderId());
    }
}
