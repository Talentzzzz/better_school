package com.betterschool.infrastructure.dao.outbox;

import com.betterschool.infrastructure.dao.outbox.po.OutboxPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * outbox 消息表 Mapper（合并后以 marketing 侧为基座）：SQL 定义在 app 模块 resources/mybatis/mapper/OutboxMapper.xml
 */
@Mapper
public interface IOutboxDao {

    int insert(OutboxPO outboxPO);

    /**
     * 扫描待重发的 PENDING 行（票 14）：只取 {@code update_time <= NOW() - timeoutSeconds} 的，
     * 给事务提交后的首次异步发送留出回旋，避免「刚提交就被扫描重发一遍」。
     */
    List<OutboxPO> selectPendingList(@Param("timeoutSeconds") int timeoutSeconds, @Param("limit") int limit);

    int markSentBatch(@Param("ids") List<Long> ids);

    int markDeadBatch(@Param("ids") List<Long> ids);

    int incrementRetryBatch(@Param("ids") List<Long> ids);
}
