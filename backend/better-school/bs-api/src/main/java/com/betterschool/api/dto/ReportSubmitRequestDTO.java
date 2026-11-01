package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 举报提交（登录用户）：帖子/评论/用户（跑腿订单不纳入举报） */
@Data
public class ReportSubmitRequestDTO {

    /** 举报对象 POST/COMMENT/USER */
    @NotBlank(message = "举报对象类型必填")
    private String targetType;

    @NotNull(message = "举报对象不能为空")
    private String targetId;

    @NotBlank(message = "举报理由必填")
    private String reason;
}
