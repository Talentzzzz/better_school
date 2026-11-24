package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 feed 索引行扫描结果（market-feed-uv 票 04）：product_feed_index 的排序键投影，
 * 「出 ID → 回查组装」读路径的第一段——排序在这里定，内容由 {@code listByIds} 批量回查。
 * 排序键（feed_time / view_count / price）随行带出，供游标续页锚点（encode 下一页 cursor）使用
 *
 * <p>与帖子 {@code FeedIndexItemVO} 同构，只把 like_count 换成市集的两类键（热度 + 价格）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductFeedIndexItemVO {

    /** 商品业务 ID（UUID 字符串，uuid-biz-id 票 05） */
    private String productId;

    /** 「最新 / 最早」排序键（通过时刻） */
    private LocalDateTime feedTime;

    /** 「热度」同值收敛键（发布时刻快照，uuid-biz-id 票 05：view_count 同值按 create_time DESC 收敛） */
    private LocalDateTime createTime;

    /** 「价格升降」排序键（价格快照） */
    private BigDecimal price;

    /** 「热度」排序键（浏览 UV 数快照） */
    private int viewCount;
}
