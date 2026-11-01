package com.betterschool.infrastructure.adapter.screen;

import com.betterschool.domain.moderation.adapter.repository.IModerationRepository;
import com.betterschool.domain.moderation.model.entity.SensitiveWordEntity;
import com.betterschool.domain.moderation.service.IModerationScreenService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * 关键词筛查实现：内存词表 contains 匹配。
 * 启动加载 + admin 增删后刷新（读写收敛在此类内）；多实例一致性后置（本期单体）
 */
@Repository
@RequiredArgsConstructor
public class KeywordScreenServiceImpl implements IModerationScreenService {

    private final IModerationRepository moderationRepository;

    private volatile Set<String> wordSet = Set.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    private void refresh() {
        wordSet = Set.copyOf(moderationRepository.listEnabledWords());
    }

    @Override
    public boolean screen(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String word : wordSet) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void adminAddWord(String word) {
        moderationRepository.saveWord(SensitiveWordEntity.create(word));
        refresh();
    }

    @Override
    public void adminDeleteWord(Long wordId) {
        moderationRepository.deleteWord(wordId);
        refresh();
    }

    @Override
    public List<SensitiveWordEntity> listWords(int page, int pageSize) {
        return moderationRepository.pageWords((page - 1) * pageSize, pageSize);
    }

    @Override
    public long countWords() {
        return moderationRepository.countWords();
    }
}
