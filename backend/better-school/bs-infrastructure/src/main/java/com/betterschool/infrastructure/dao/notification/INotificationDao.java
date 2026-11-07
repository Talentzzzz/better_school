package com.betterschool.infrastructure.dao.notification;

import com.betterschool.infrastructure.dao.notification.po.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 站内通知 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/NotificationMapper.xml
 */
@Mapper
public interface INotificationDao {

    /** 写入事务内取号：行不存在按 seq=1 建行，存在则 +1（行锁到提交 ⇒ 取号序 = 提交序） */
    int upsertIncreaseSeq(@Param("userId") String userId, @Param("scope") String scope);

    /** 当前计数器值；该 (user, scope) 从未取过号为 null */
    Long selectSeq(@Param("userId") String userId, @Param("scope") String scope);

    Long selectLastReadSeq(@Param("userId") String userId, @Param("scope") String scope);

    /** 推水位：GREATEST 只前进不回退 */
    int upsertReadWatermark(@Param("userId") String userId, @Param("scope") String scope,
                            @Param("lastReadSeq") long lastReadSeq);

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
}
