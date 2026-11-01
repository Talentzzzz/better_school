package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminAdjustRequestDTO {

    @NotNull(message = "目标用户不能为空")
    private String targetUserId;

    /** 调分量（±1 ~ ±50，非 0） */
    @NotNull(message = "调分量不能为空")
    private Integer delta;

    @NotBlank(message = "调分原因必填")
    private String reason;
}
