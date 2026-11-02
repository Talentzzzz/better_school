package com.betterschool.marketing.api.points.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水一行（入账与消费两表按时间归并后的统一形态）。
 * <p>
 * {@code id} 是各自表内自增 id——两张表的 id 空间不同，列表项唯一性靠 {@code (direction, id)}；
 * 一笔兑换跨多个桶消费会出多行 OUT（每桶一行，保留可审计的扣减明细）。
 */
@Data
public class PointsTransactionResponseDTO {

    private Long id;
    /** 方向 IN（入账，来自 points_bucket）/ OUT（消费，来自 points_detail） */
    private String direction;
    /** 变动积分数（恒正，方向看 direction） */
    private Integer amount;
    /** LOTTERY_WIN / REDEEM_DRAW_COUNT / REDEEM_PRIZE */
    private String reasonType;
    /** 来源业务 id：入账 = 抽奖记录 id、出账 = 兑换单 id */
    private String refId;
    private LocalDateTime createdAt;
    /** 该桶的过期时间（仅 IN 行有；OUT 行为 null） */
    private LocalDateTime expireAt;
}
