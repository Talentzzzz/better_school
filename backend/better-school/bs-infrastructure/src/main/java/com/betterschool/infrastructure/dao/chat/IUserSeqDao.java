package com.betterschool.infrastructure.dao.chat;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * user_seq 表 DAO：SQL 定义在 app 模块 resources/mybatis/mapper/UserSeqMapper.xml。
 * per-user delivery_seq 计数器
 */
@Mapper
public interface IUserSeqDao {

    /** 计数器行不存在时按 seq=N 建行，存在则 +N */
    int upsertIncreaseUserSeq(@Param("userId") String userId, @Param("n") int n);

    Long selectUserSeq(@Param("userId") String userId);
}
