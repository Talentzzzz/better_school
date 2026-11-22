package com.betterschool.api.points.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 扣积分请求（Dubbo 同步边：抽奖服务 → 积分服务）。
 * <p>
 * 幂等键 = {@code refId}（兑换单 id）：抽奖侧重试必须带同一个 {@code refId}，
 * 积分侧按 {@code (user_id, reason_type, ref_id)} 判定「这笔兑换已经扣过」直接跳过。
 */
@Data
public class PointsDeductRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;
    /** 扣减积分数（恒正） */
    private Integer amount;
    /** 只接受 REDEEM_DRAW_COUNT / REDEEM_PRIZE 两个值（字符串而非枚举：跨服务序列化最稳） */
    private String reasonType;
    /** 兑换单 id（幂等键） */
    private String refId;
}
