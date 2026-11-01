package com.betterschool.domain.moderation.service;

import com.betterschool.domain.moderation.model.entity.SensitiveWordEntity;

import java.util.List;

/**
 * 内容筛查（关键词过滤）：
 * 私信命中 → 直接拒绝发送；帖子/评论/跑腿命中 → 进人工审核队列。
 */
public interface IModerationScreenService {

    /** 是否命中敏感词（内存词表 contains 匹配） */
    boolean screen(String text);

    /** admin 新增敏感词（uk(word) 幂等，成功后刷新缓存） */
    void adminAddWord(String word);

    /** admin 删除敏感词（成功后刷新缓存） */
    void adminDeleteWord(Long wordId);

    List<SensitiveWordEntity> listWords(int page, int pageSize);

    long countWords();
}
