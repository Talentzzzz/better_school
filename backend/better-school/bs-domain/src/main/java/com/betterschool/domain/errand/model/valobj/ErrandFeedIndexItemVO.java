package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跑腿 feed 索引行扫描结果（market-feed-uv 票 04）：errand_feed_index 的排序键投影，
 * 「出 ID → 回查组装」读路径的第一段——排序在这里定，内容由 {@code listByIds} 批量回查。
 * 排序键（feed_time / create_time / view_count / reward）随行带出，供游标续页锚点
 * （encode 下一页 cursor）使用
 *
 * <p>与商品 {@code ProductFeedIndexItemVO} 同构，只把业务键换成 {@code errand_order_id}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrandFeedIndexItemVO {

    /** 跑腿订单业务 ID（UUID 字符串，uuid-biz-id 票 05）；列名 errand_order_id 直落，字段随列名 */
    private String errandOrderId;

    /** 「最新 / 最早」排序键（进入大厅的时刻） */
    private LocalDateTime feedTime;

    /** 「热度」档同值收敛键（订单创建时刻快照，uuid-biz-id 票 05：UUID 无序，不能再拿业务 ID 当时间代理） */
    private LocalDateTime createTime;

    /** 「价格升降」排序键（悬赏 reward 的快照；索引表列名沿用两域统一的 price） */
    private BigDecimal price;

    /** 「热度」排序键（浏览 UV 数快照） */
    private int viewCount;
}
