package com.betterschool.marketing.api.points.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 扣积分结果（Dubbo 同步边）。业务失败走 {@code success = false} + {@code code}/{@code info}，
 * 不抛异常——抽奖侧要把「积分不足」原样变成自己的业务码返回前端。
 */
@Data
public class PointsDeductResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    /** 成功固定 "200"；失败为积分服务业务码（当前只有 POINTS_INSUFFICIENT_POINTS = "4101"） */
    private String code;
    /** 失败文案（"积分不足"） */
    private String info;
    /** 扣减后余额（成功时有值；幂等重放时也给出当前余额） */
    private Integer balance;
}
