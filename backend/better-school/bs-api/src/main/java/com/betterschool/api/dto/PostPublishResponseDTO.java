package com.betterschool.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发帖 / 草稿提交响应：发布的帖子 ID + 本次返利领取结果。
 * <p>
 * 老客户端忽略新增字段仍可正常发布（HTTP 200 与 postId 语义不变）；前端按 {@code rebateState}
 * 组装 toast：CLAIMED 展示两个金额，DENIED 提示今日已领，NO_REBATE 只显示发布成功，
 * CLAIM_ERROR 提示发放异常（此时金额恒 0）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostPublishResponseDTO {

    private String postId;

    /** CLAIMED / DENIED / NO_REBATE / CLAIM_ERROR（内部 GRANTED 已折叠成 CLAIMED） */
    private String rebateState;

    private int pointsRebate;

    private int drawCountRebate;
}
