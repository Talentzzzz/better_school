package com.betterschool.infrastructure.dao.outbox;

import com.betterschool.infrastructure.dao.outbox.po.OutboxPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * outbox 消息表 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/OutboxMapper.xml
 */
@Mapper
public interface IOutboxDao {

    int insert(OutboxPO outboxPO);

    List<OutboxPO> selectPendingList(@Param("limit") int limit);

    int markSentBatch(@Param("ids") List<Long> ids);

    int markDeadBatch(@Param("ids") List<Long> ids);

    int incrementRetryBatch(@Param("ids") List<Long> ids);
}
