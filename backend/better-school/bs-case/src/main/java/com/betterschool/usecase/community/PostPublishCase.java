package com.betterschool.usecase.community;

import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.service.post.ICommunityPostService;
import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发帖用例编排：信用分门槛前置校验 + community 域发布（落库即可见）。
 * 草稿同轨：保存/编辑私有内容不做校验，提交与直接发布同门槛校验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostPublishCase {

    private final ICommunityPostService communityPostService;
    private final ICreditAccountService creditAccountService;

    public String publishPost(String userId, String title, String content,
                              List<String> images, String cover) {
        creditAccountService.ensureOperable(userId);
        return communityPostService.publishPost(userId, title, content, images, cover);
    }

    /** 保存帖子草稿：不做信用分门槛，字段可缺省 */
    public String savePostDraft(String userId, String title, String content, List<String> images,
                                String cover) {
        return communityPostService.savePostDraft(userId, title, content, images, cover);
    }

    /** 编辑帖子草稿：仅 DRAFT 可编辑 */
    public void updatePostDraft(String userId, String postId, String title, String content,
                                List<String> images, String cover) {
        communityPostService.updatePostDraft(userId, postId, title, content, images, cover);
    }

    /** 提交帖子草稿：与直接发布同门槛校验，通过即发布可见 */
    public void submitPostDraft(String userId, String postId) {
        PostEntity draftEntity = communityPostService.getOwnedPostDraft(userId, postId);
        creditAccountService.ensureOperable(userId);
        validatePostDraftComplete(draftEntity);
        communityPostService.submitPostDraft(userId, postId);
    }

    /** 草稿用例层完整性校验：正文必填，标题/配图/封面可空 */
    private void validatePostDraftComplete(PostEntity draftEntity) {
        if (draftEntity.getContent() == null || draftEntity.getContent().isBlank()) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿内容不完整，请补全正文后再提交");
        }
    }
}
