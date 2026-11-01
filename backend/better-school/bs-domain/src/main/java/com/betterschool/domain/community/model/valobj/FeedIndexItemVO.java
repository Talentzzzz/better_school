package com.betterschool.domain.community.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * feed 索引行扫描结果（feed-index-uv 票 04）：post_feed_index 的排序键投影，
 * 「出 ID → 回查组装」读路径的第一段——排序在这里定，内容由 {@code listByIds} 批量回查。
 * 排序键随行带出，供游标续页锚点（encode 下一页 cursor）使用
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedIndexItemVO {

    private String postId;

    /** 「最新」排序键（通过时刻）；「热榜」同值收敛也用它（UUID 业务 ID 无序，不能拿它当时间代理） */
    private LocalDateTime feedTime;

    /** 「热榜」排序键（点赞数快照） */
    private int likeCount;
}
