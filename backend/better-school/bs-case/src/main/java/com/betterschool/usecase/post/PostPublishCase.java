package com.betterschool.usecase.post;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.domain.raffle.service.drawcount.IDrawCountAccountService;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.rebate.RebateActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发帖用例编排：信用分门槛前置校验 + post 域发布（落库即可见）+ 发帖返利。
 * 草稿同轨：保存/编辑私有内容不做校验，提交与直接发布同门槛校验。
 * <p>
 * 返利不再由发帖请求线程直接发放：发布仓储在事务内落一条 outbox 任务，提交后同步发送到 MQ
 * （broker 确认即标记 SENT，失败留 PENDING 交 {@code outboxScan}），由 {@code PostRebateConsumer}
 * 调 {@link #grantPostRebate} 发放；桶唯一键 {@code (userId, REBATE, postId)} 吸收重复投递。
 */
@Service
@RequiredArgsConstructor
public class PostPublishCase {

    private final IPostService postService;
    private final ICreditAccountService creditAccountService;
    private final IPointsAccountService pointsAccountService;
    private final IDrawCountAccountService drawCountAccountService;

    public String publishPost(String userId, String title, String content,
                              List<String> images, String cover) {
        creditAccountService.ensureOperable(userId);
        return postService.publishPost(userId, title, content, images, cover);
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

    /** 提交帖子草稿：与直接发布同门槛校验，通过即发布可见 */
    public void submitPostDraft(String userId, String postId) {
        PostEntity draftEntity = postService.getOwnedPostDraft(userId, postId);
        creditAccountService.ensureOperable(userId);
        validatePostDraftComplete(draftEntity);
        postService.submitPostDraft(userId, postId);
    }

    /**
     * 返利发放入口（{@code PostRebateConsumer} 调用）：只做发放，失败上抛给调用方。
     * 消费者上抛 → broker 重试；桶唯一键 {@code (userId, REBATE, postId)} 吸收重投。
     */
    public void grantPostRebate(String userId, String postId) {
        String actionType = RebateActionType.POST.getCode();
        pointsAccountService.creditForRebate(actionType, userId, postId);
        drawCountAccountService.creditForRebate(actionType, userId, postId);
    }

    /** 草稿用例层完整性校验：正文必填，标题/配图/封面可空 */
    private void validatePostDraftComplete(PostEntity draftEntity) {
        if (draftEntity.getContent() == null || draftEntity.getContent().isBlank()) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿内容不完整，请补全正文后再提交");
        }
    }
}
