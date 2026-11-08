package com.betterschool.infrastructure.dao.moderation;

import com.betterschool.infrastructure.dao.moderation.po.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 敏感词表 Mapper（sensitive_word 单表），
 * SQL 定义在 app 模块 resources/mybatis/mapper/SensitiveWordMapper.xml
 */
@Mapper
public interface ISensitiveWordDao {

    int insertIgnoreWord(SensitiveWord word);

    int deleteWord(@Param("wordId") Long wordId);

    List<String> selectEnabledWords();

    List<SensitiveWord> selectWords(@Param("offset") int offset, @Param("limit") int limit);

    long countWords();
}
