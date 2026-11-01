package com.betterschool.domain.community.model.entity;

import com.betterschool.domain.community.model.valobj.PostStatusVO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PostCommentEntity {

    /** 楼根/父评论的哨兵值：业务 ID 是 UUID 字符串，根评论的 root_id / parent_id 落字符串 "0" */
    public static final String NO_REFERENCE = "0";

    private String commentId;
    private String postId;
    private String userId;
    private String content;
    private PostStatusVO status;
    /** 楼根评论业务 ID（"0"=根评论），语义见 ADR-0001 */
    private String rootId;
    /** 被回复评论业务 ID（根评论为 "0"；回复根评论时=根评论 id） */
    private String parentId;
    /** 评论点赞数（根评论与子回复通用，点赞记录同事务 ±1） */
    private Long likeCount;
    /** 楼内可见子回复数（仅根评论有意义，仅 NORMAL 计入） */
    private Long replyCount;
    private LocalDateTime createdAt;

    /** 查询冗余字段：作者昵称（列表 JOIN 填充） */
    private String authorNickname;

    /** 查询冗余字段：被回复人 id/昵称（楼内列表 JOIN parent 行填充，忽略其状态） */
    private String replyToUserId;
    private String replyToNickname;

    /** 查询冗余字段：审核未通过理由（列表 JOIN content_review REJECT 行填充，仅作者本人场景回显） */
    private String rejectReason;

    /** 查询冗余字段：来源帖标题/封面（按用户列评论场景 JOIN post 填充） */
    private String postTitle;
    private String postCover;

    public static PostCommentEntity create(String postId, String userId, String content, PostStatusVO initialStatus) {
        PostCommentEntity commentEntity = new PostCommentEntity();
        commentEntity.postId = postId;
        commentEntity.userId = userId;
        commentEntity.content = content;
        commentEntity.status = initialStatus;
        commentEntity.rootId = NO_REFERENCE;
        commentEntity.parentId = NO_REFERENCE;
        commentEntity.createdAt = LocalDateTime.now();
        return commentEntity;
    }

    public static PostCommentEntity createReply(String postId, String rootId, String parentId, String userId,
                                                String content, PostStatusVO initialStatus) {
        PostCommentEntity commentEntity = create(postId, userId, content, initialStatus);
        commentEntity.rootId = rootId;
        commentEntity.parentId = parentId;
        return commentEntity;
    }

    /** 是否根评论（root_id = "0"）：子回复才入楼、才可能带「回复指向」 */
    public boolean isRoot() {
        return NO_REFERENCE.equals(rootId);
    }
}
