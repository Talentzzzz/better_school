package com.betterschool.infrastructure.dao.notification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 站内通知已读水位 Mapper（notification_read 单表），
 * SQL 定义在 app 模块 resources/mybatis/mapper/NotificationReadMapper.xml
 */
@Mapper
public interface INotificationReadDao {

    Long selectLastReadSeq(@Param("userId") String userId, @Param("scope") String scope);

    /** 推水位：GREATEST 只前进不回退 */
    int upsertReadWatermark(@Param("userId") String userId, @Param("scope") String scope,
                            @Param("lastReadSeq") long lastReadSeq);
}
