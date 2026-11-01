package com.betterschool.marketing.api.lottery.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的奖品」的一条（四段状态共用同一份结构）＝ 中奖记录中非谢谢参与的行
 * （draw_record 快照，旧模型的中奖/兑换汇流两张来源合一不再存在）。
 * <p>
 * 相对旧模型：{@code image} 无新模型载体已删除；{@code prizeType} 取值
 * VIRTUAL / PHYSICAL / THANKS；{@code status} 用 PENDING(待兑换) / EXCHANGED(已兑换) /
 * SHIPPED(已发货) / COMPLETED(已完成) 表达四段状态（旧 PENDING / RECEIVED / CREDITED 三态）。
 */
@Data
public class UserPrizeResponseDTO {

    private Long id;
    private Long prizeId;
    private Integer tier;
    private String name;
    /** VIRTUAL / PHYSICAL / THANKS（列表恒不含 THANKS） */
    private String prizeType;
    /** PENDING(待兑换) / EXCHANGED(已兑换) / SHIPPED(已发货) / COMPLETED(已完成) */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime claimedAt;
}
