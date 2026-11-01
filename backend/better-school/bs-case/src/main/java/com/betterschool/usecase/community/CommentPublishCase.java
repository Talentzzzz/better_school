package com.betterschool.usecase.community;

import com.betterschool.domain.community.model.entity.PostCommentEntity;
import com.betterschool.domain.community.service.comment.ICommunityCommentService;
import com.betterschool.domain.moderation.service.IModerationScreenService;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 评论发布用例编排（ADR-0004）：同步初筛命中即报错不落库（与帖子/商品/跑腿语义一致）；通过初筛落库即待审。
 * 评论无草稿、无重提：初筛命中直接报错，用户修改后重发即可
 */
@Service
@RequiredArgsConstructor
public class CommentPublishCase {

    private final ICommunityCommentService communityCommentService;
    private final IModerationScreenService moderationScreenService;

    public PostCommentEntity addComment(String postId, String userId, String content) {
        screenText(content);
        return communityCommentService.addComment(postId, userId, content);
    }

    public PostCommentEntity addReply(String targetCommentId, String userId, String content) {
        screenText(content);
        return communityCommentService.addReply(targetCommentId, userId, content);
    }

    /** 同步初筛：命中即业务失败，发布不落库（初筛不覆盖图片，仅文字） */
    private void screenText(String content) {
        if (moderationScreenService.screen(content == null ? "" : content)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "内容包含违规词汇，请修改后重试");
        }
    }
}
