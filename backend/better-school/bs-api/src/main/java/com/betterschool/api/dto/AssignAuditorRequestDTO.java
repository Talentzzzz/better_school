package com.betterschool.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 指派/回收审核员（admin） */
@Data
public class AssignAuditorRequestDTO {

    @NotNull(message = "用户不能为空")
    private String userId;
}
