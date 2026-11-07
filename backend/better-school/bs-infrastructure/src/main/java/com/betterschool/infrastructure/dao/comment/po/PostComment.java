package com.betterschool.infrastructure.dao.comment.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PostComment {

    private Long id;
    /** 业务 ID（对外唯一标识，UUID 字符串；应用层 insert 前发号一次写入，与物理 id 永不同值） */
    private String commentId;
    private String postId;
    private String userId;
    private String content;
    private String state;
    private String rootId;
    private String parentId;
    /**
     * 点赞数：<b>投影填充字段</b>，post_comment 表里没有这一列（票 04 已删）。
     * 真源 post_comment_like_count，由仓储按页内 id 集批量回查后填进来（缺行补 0）
     */
    private Long likeCount;
    private Long replyCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 查询冗余字段：作者昵称（仓储回查 bs_user 填充） */
    private String authorNickname;

    /** 查询冗余字段：被回复人 id/昵称（楼内列表由仓储单表回查 parent 行填充，忽略其状态） */
    private String replyToUserId;
    private String replyToNickname;

    /** 查询冗余字段：审核未通过理由（仓储回查 content_review 最新 REJECT 行填充） */
    private String rejectReason;

    /** 查询冗余字段：来源帖标题/封面（按用户列评论由仓储回查 post 填充，评论行本身不存） */
    private String postTitle;
    private String postCover;
}
