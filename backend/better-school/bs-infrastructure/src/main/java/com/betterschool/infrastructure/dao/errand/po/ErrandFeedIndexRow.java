package com.betterschool.infrastructure.dao.errand.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跑腿大厅索引重建行：仓储多查（errand_order 行 / view_count 窄表）
 * 拼装后的写回载体，一条批量 INSERT 落 errand_feed_index（原 INSERT..SELECT JOIN 投影的拆装形态）。
 */
@Data
public class ErrandFeedIndexRow {

    private String errandOrderId;
    private String state;
    /** errand_order.create_time（feed_time 取值源 + 索引 create_time 快照：热度档同值收敛键） */
    private LocalDateTime createTime;
    /** 仓储组装：feed_time = createTime */
    private LocalDateTime feedTime;
    /** errand_order.reward 快照（索引列 price 的取值源，与 upsertFeedIndexOnApprove 同口径）；字段随列名 */
    private BigDecimal reward;
    /** 从计数窄表抄入（无行 = 0） */
    private Integer viewCount;
}
