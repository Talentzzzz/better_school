package com.betterschool.usecase.post;

import com.betterschool.domain.post.model.entity.PostCommentEntity;
import com.betterschool.domain.post.service.post.IPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 评论发布用例编排：落库即可见。评论无草稿、无重提
 */
@Service
@RequiredArgsConstructor
public class CommentPublishCase {

    private final IPostService postService;


    public PostCommentEntity addComment(String postId, String userId, String content) {
        return postService.addComment(postId, userId, content);
    }

    public PostCommentEntity addReply(String targetCommentId, String userId, String content) {
        return postService.addReply(targetCommentId, userId, content);
    }
}
