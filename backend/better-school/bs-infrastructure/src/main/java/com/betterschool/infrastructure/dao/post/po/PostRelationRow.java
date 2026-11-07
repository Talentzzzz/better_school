package com.betterschool.infrastructure.dao.post.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关系表主查行（我赞过的 / 我评论过的 / 浏览历史）：关系表单表扫描的排序键 + 业务 id，
 * 帖子行由仓储按 id 集二次批量回查拼装（SQL 层零 JOIN）。三类关系共用一个载体，
 * {@code sortTime} 按语句语义分别是点赞时刻 / 最近评论时刻 / 浏览时刻。
 */
@Data
public class PostRelationRow {

    private String postId;

    /** 排序键：点赞时刻（post_like.create_time）/ 最近评论时刻（MAX(post_comment.create_time)）/ 浏览时刻（viewed_at） */
    private LocalDateTime sortTime;
}
