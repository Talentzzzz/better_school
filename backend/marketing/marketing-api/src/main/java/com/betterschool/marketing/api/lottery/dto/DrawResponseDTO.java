package com.betterschool.marketing.api.lottery.dto;

import lombok.Data;

/**
 * 单抽结果：**谢谢参与也走成功**（{@code prizeType = THANKS}），前端据此弹第三种弹窗。
 * 幂等重放返回的是同一份内容（从已存记录快照复算）。
 * <p>
 * 相对旧模型：{@code image} 无新模型载体已删除；{@code prizeType} 取值
 * VIRTUAL / PHYSICAL / THANKS（旧 POINTS 档归入 VIRTUAL）。
 */
@Data
public class DrawResponseDTO {

    private Long recordId;
    /** VIRTUAL / PHYSICAL / THANKS */
    private String prizeType;
    private Long prizeId;
    private Integer tier;
    private String name;
    /** 积分档发放的积分数，其余为 null */
    private Integer pointsAmount;
}
