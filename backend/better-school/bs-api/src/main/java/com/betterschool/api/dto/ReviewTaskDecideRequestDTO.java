package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 审核工单裁决（审核员，spec §5.2 强绑定）：仅持有人在租约内可裁 */
@Data
public class ReviewTaskDecideRequestDTO {

    /** 动作 APPROVE(通过)/REJECT(驳回) */
    @NotBlank(message = "审核动作必填")
    private String action;

    /** 拒绝理由枚举 code（RejectReasonCode，拒绝必填；通过时可空） */
    private String reasonCode;

    /** 可选补充说明（落库为"枚举文案：补充说明"） */
    private String supplement;
}
