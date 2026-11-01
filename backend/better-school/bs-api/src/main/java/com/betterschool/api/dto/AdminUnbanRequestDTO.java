package com.betterschool.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminUnbanRequestDTO {

    @NotNull(message = "目标用户不能为空")
    private String targetUserId;
}
