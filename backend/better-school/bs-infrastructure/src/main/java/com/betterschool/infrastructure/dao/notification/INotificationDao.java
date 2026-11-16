package com.betterschool.infrastructure.dao.notification;

import com.betterschool.infrastructure.dao.notification.po.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 站内通知 Mapper（notification 单表）：取号/水位语句见 INotificationSeqDao/INotificationReadDao，
 * SQL 定义在 app 模块 resources/mybatis/mapper/NotificationMapper.xml
 */
@Mapper
public interface INotificationDao {

    int insert(Notification notification);

    /** 互动通知落库：dedup_key 唯一键冲突（同一触发者对同一目标已发过）静默忽略（并发窗口兜底） */
    int insertIgnore(Notification notification);

    /** is_read 为计算列别名（seq ≤ 该范围水位），非表列 */
    List<Notification> selectPage(@Param("userId") String userId, @Param("scope") String scope,
                                    @Param("offset") int offset, @Param("limit") int limit);

    long count(@Param("userId") String userId, @Param("scope") String scope);

    int existsBroadcast(@Param("msgId") String msgId);

    /** dedup_key 命中检查：先查后插避免重复消费白耗 seq 号段 */
    int existsDedupKey(@Param("dedupKey") String dedupKey);

    /** notification_id 命中检查：无 dedupKey 的事件重试时防普通 insert 撞 uk_notification_id 抛 DuplicateKeyException */
    int existsNotificationId(@Param("notificationId") String notificationId);
}
