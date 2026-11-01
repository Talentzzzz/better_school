package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 统一裁决请求（管理端）：通过或拒绝 */
@Data
public class ReviewDecideRequestDTO {

    /** 审核对象 POST/COMMENT/ERRAND */
    @NotBlank(message = "审核对象类型必填")
    private String targetType;

    @NotNull(message = "审核对象不能为空")
    private String targetId;

    /** true=通过发布，false=拒绝（拒绝时 reasonCode 必填且为合法枚举） */
    @NotNull(message = "裁决结果必填")
    private Boolean approve;

    /** 拒绝理由枚举 code（RejectReasonCode，拒绝必填；通过时可空） */
    private String reasonCode;

    /** 可选补充说明（落库为"枚举文案：补充说明"） */
    private String supplement;
}
