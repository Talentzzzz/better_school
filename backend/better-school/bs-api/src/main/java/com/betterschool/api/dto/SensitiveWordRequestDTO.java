package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 敏感词新增（admin） */
@Data
public class SensitiveWordRequestDTO {

    @NotBlank(message = "敏感词必填")
    @Size(max = 50, message = "敏感词最长 50 字")
    private String word;
}
