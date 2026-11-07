package com.betterschool.infrastructure.dao.comment.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 热评索引重建行：仓储三段查（根评论行 / like_count 窄表 / content_review APPROVE 流水）拼装后的
 * 写回载体，一条批量 INSERT 落 post_comment_feed_index（原 INSERT..SELECT JOIN 投影的拆装形态）。
 */
@Data
public class CommentFeedIndexRow {

    private String commentId;
    private String postId;
    /** 评论 create_time（feed_time 的兜底源，查不到审批流水时用） */
    private LocalDateTime createTime;
    /** 审核记录最近一次 APPROVE 时刻（仓储组装：COALESCE(approveTime, createTime)） */
    private LocalDateTime feedTime;
    /** 从计数窄表抄入（无行 = 0，与运行期「窄表无行即计数 0」同口径） */
    private Long likeCount;
}
