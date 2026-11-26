package com.betterschool.usecase.post;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.valobj.PostPublishResultVO;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发帖用例编排：信用分门槛前置校验 + post 域发布（落库即可见）+ 同步领取发帖返利。
 * 草稿同轨：保存/编辑私有内容不做校验，提交与直接发布同门槛校验。
 * <p>
 * 返利两段式：发布事务提交后，请求线程同步领取（{@link PostRebateCase#claimForPost}），
 * 领取结果（CLAIMED / DENIED / NO_REBATE）随发布响应返回；积分 / 抽奖次数入账由领取事务内
 * 落下的 credit outbox 异步完成（{@link PostRebateCase#credit}），失败交 broker 重试。
 * 领取阶段异常不影响已提交的发布——响应 PENDING，领取任务留 PENDING 由 {@code outboxScan} 重放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostPublishCase {

    private final IPostService postService;
    private final ICreditAccountService creditAccountService;
    private final PostRebateCase postRebateCase;

    /** 直接发布：门槛校验通过后发布，再同步领取返利并返回领取结果；refId 即新 postId */
    public RebateClaimResultVO publishPost(String userId, String title, String content,
                                           List<String> images, String cover) {
        creditAccountService.ensureOperable(userId);
        PostPublishResultVO publishResult = postService.publishPost(userId, title, content, images, cover);
        try {
            return postRebateCase.claimForPost(userId, publishResult.getPostId(), publishResult.getPublishTime());
        } catch (Exception e) {
            log.warn("发帖返利同步领取失败，响应 PENDING 等待重放，postId={}", publishResult.getPostId(), e);
            return RebateClaimResultVO.pending(publishResult.getPostId());
        }
    }

    /** 保存帖子草稿：不做信用分门槛，字段可缺省 */
    public String savePostDraft(String userId, String title, String content, List<String> images,
                                String cover) {
        return postService.savePostDraft(userId, title, content, images, cover);
    }

    /** 编辑帖子草稿：仅 DRAFT 可编辑 */
    public void updatePostDraft(String userId, String postId, String title, String content,
                                List<String> images, String cover) {
        postService.updatePostDraft(userId, postId, title, content, images, cover);
    }

    /** 提交帖子草稿：与直接发布同门槛校验，通过即发布可见，并同步领取返利 */
    public RebateClaimResultVO submitPostDraft(String userId, String postId) {
        PostEntity draftEntity = postService.getOwnedPostDraft(userId, postId);
        creditAccountService.ensureOperable(userId);
        validatePostDraftComplete(draftEntity);
        PostPublishResultVO publishResult = postService.submitPostDraft(userId, postId);
        try {
            return postRebateCase.claimForPost(userId, publishResult.getPostId(), publishResult.getPublishTime());
        } catch (Exception e) {
            log.warn("提交草稿后发帖返利同步领取失败，响应 PENDING 等待重放，postId={}", publishResult.getPostId(), e);
            return RebateClaimResultVO.pending(publishResult.getPostId());
        }
    }

    /** 草稿用例层完整性校验：正文必填，标题/配图/封面可空 */
    private void validatePostDraftComplete(PostEntity draftEntity) {
        if (draftEntity.getContent() == null || draftEntity.getContent().isBlank()) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿内容不完整，请补全正文后再提交");
        }
    }
}
