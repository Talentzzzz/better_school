package com.betterschool.marketing.api.lottery.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的中奖记录的一条（**过滤掉谢谢参与**）。奖品信息是抽奖时的落库快照，不关联奖品配置。
 * <p>
 * 相对旧模型：{@code image} 无新模型载体已删除；{@code prizeType} 取值
 * VIRTUAL / PHYSICAL / THANKS（旧 POINTS 档归入 VIRTUAL）。
 */
@Data
public class DrawRecordResponseDTO {

    private String recordId;
    private String prizeId;
    private Integer tier;
    private String name;
    /** VIRTUAL / PHYSICAL / THANKS */
    private String prizeType;
    private Integer pointsAmount;
    private LocalDateTime createdAt;
}
