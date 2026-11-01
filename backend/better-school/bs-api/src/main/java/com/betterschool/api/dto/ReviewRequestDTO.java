package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 人工审核动作（审核员）：通过 / 驳回 */
@Data
public class ReviewRequestDTO {

    /** 审核对象 POST/COMMENT/ERRAND */
    @NotBlank(message = "审核对象类型必填")
    private String targetType;

    @NotNull(message = "审核对象不能为空")
    private String targetId;

    /** 动作 APPROVE(通过)/REJECT(驳回) */
    @NotBlank(message = "审核动作必填")
    private String action;

    /** 拒绝理由枚举 code（RejectReasonCode，拒绝必填；通过时可空） */
    private String reasonCode;

    /** 可选补充说明（落库为"枚举文案：补充说明"） */
    private String supplement;
}
