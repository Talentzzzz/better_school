package com.betterschool.infrastructure.dao.notification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 站内通知取号计数器 Mapper（notification_seq 单表），
 * SQL 定义在 app 模块 resources/mybatis/mapper/NotificationSeqMapper.xml
 */
@Mapper
public interface INotificationSeqDao {

    /** 写入事务内取号：行不存在按 seq=1 建行，存在则 +1（行锁到提交 ⇒ 取号序 = 提交序） */
    int upsertIncreaseSeq(@Param("userId") String userId, @Param("scope") String scope);

    /** 当前计数器值；该 (user, scope) 从未取过号为 null */
    Long selectSeq(@Param("userId") String userId, @Param("scope") String scope);
}
