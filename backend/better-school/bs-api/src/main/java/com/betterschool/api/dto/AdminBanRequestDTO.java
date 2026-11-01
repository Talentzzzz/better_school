package com.betterschool.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminBanRequestDTO {

    @NotNull(message = "目标用户不能为空")
    private String targetUserId;

    /** 封禁原因（可空，随通知下发） */
    private String reason;
}
