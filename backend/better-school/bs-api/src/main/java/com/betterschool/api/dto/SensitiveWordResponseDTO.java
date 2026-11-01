package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 敏感词（admin 列表） */
@Data
public class SensitiveWordResponseDTO {

    private Long id;
    private String word;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
