package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.time.LocalDateTime;

/** sensitive_word 敏感词库表 PO */
@Data
public class SensitiveWordPO {

    private Long id;
    private String word;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
