package com.betterschool.usecase.community;

import com.betterschool.domain.community.model.entity.PostCommentEntity;
import com.betterschool.domain.community.service.comment.ICommunityCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 评论发布用例编排：落库即可见。评论无草稿、无重提
 */
@Service
@RequiredArgsConstructor
public class CommentPublishCase {

    private final ICommunityCommentService communityCommentService;

    public PostCommentEntity addComment(String postId, String userId, String content) {
        return communityCommentService.addComment(postId, userId, content);
    }

    public PostCommentEntity addReply(String targetCommentId, String userId, String content) {
        return communityCommentService.addReply(targetCommentId, userId, content);
    }
}
