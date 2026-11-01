package com.betterschool.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑资料（整体覆盖可编辑的两字段）：signature 空串/null＝清空，gender null/空串＝不展示
 */
@Data
public class UserProfileUpdateRequestDTO {

    @Size(max = 40, message = "签名最多 40 字")
    private String signature;

    @Pattern(regexp = "MALE|FEMALE|", message = "性别不合法（MALE/FEMALE/不展示）")
    private String gender;
}
