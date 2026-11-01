package com.betterschool.domain.moderation.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 敏感词（uk(word) 防重；启动加载内存匹配，admin 增删后刷新缓存） */
@Data
public class SensitiveWordEntity {

    private Long id;
    private String word;
    private boolean enabled;
    private LocalDateTime createdAt;

    public static SensitiveWordEntity create(String word) {
        SensitiveWordEntity wordEntity = new SensitiveWordEntity();
        wordEntity.word = word;
        wordEntity.enabled = true;
        wordEntity.createdAt = LocalDateTime.now();
        return wordEntity;
    }
}
