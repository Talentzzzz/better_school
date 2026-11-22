package com.betterschool.domain.points.model.valobj;

import com.betterschool.types.points.PointsReasonType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水一行（入账与消费归并后的统一形态）。
 * <p>
 * {@code id} 是各自表内的自增 id——两张表 id 空间不同，列表项唯一性靠 {@code (direction, id)} 组合。
 */
@Data
public class PointsTransactionVO {

    private Long id;
    private PointsTransactionDirectionVO direction;
    private Integer amount;
    private PointsReasonType reasonType;
    private String refId;
    private LocalDateTime createdAt;
    /** 该桶的过期时间（仅 IN 行有） */
    private LocalDateTime expireAt;
}
