package com.betterschool.infrastructure.dao.post.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Post {

    private Long id;
    /** 业务 ID（对外唯一标识，UUID 字符串；应用层 insert 前发号一次写入，与物理 id 永不同值） */
    private String postId;
    private String userId;
    private String title;
    private String content;
    private String images;
    private String cover;
    private String state;
    private Integer viewCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime expireAt;

    /** 查询投影列：点赞/评论计数（窄表 post_like_count / post_comment_count 仓储批量回查填充，非 post 行列） */
    private Integer likeCount;
    private Integer commentCount;

    /** 查询冗余字段：作者昵称/头像（仓储查 bs_user 批量回查填充，SQL 层零 JOIN） */
    private String authorNickname;
    private String authorAvatar;
}
