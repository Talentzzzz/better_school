package com.betterschool.infrastructure.dao.errand.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跑腿大厅索引重建行：仓储三段查（errand_order 行 / content_review APPROVE 流水 / view_count 窄表）
 * 拼装后的写回载体，一条批量 INSERT 落 errand_feed_index（原 INSERT..SELECT JOIN 投影的拆装形态）。
 */
@Data
public class ErrandFeedIndexRow {

    private String errandOrderId;
    private String state;
    /** errand_order.create_time（feed_time 兜底源 + 索引 create_time 快照：热度档同值收敛键） */
    private LocalDateTime createTime;
    /** 审核记录最近一次 APPROVE 时刻（仓储组装：COALESCE(approveTime, createTime)） */
    private LocalDateTime feedTime;
    /** price = reward 快照（过审彼时的悬赏，与 upsertFeedIndexOnApprove 同口径） */
    private BigDecimal price;
    /** 从计数窄表抄入（无行 = 0） */
    private Integer viewCount;
}
