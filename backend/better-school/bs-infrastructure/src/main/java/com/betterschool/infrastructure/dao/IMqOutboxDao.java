package com.betterschool.infrastructure.dao;

import com.betterschool.infrastructure.dao.po.MqOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * outbox 消息表 Mapper（各仓储直连）：INSERT 与业务写同事务，提交后由仓储用
 * {@code RocketMQTemplate} 同步发送，成功再 {@link #markSentByMsgId}；发送失败留 PENDING，
 * 由 {@code OutboxScanJob} 扫描重发。SQL 定义在 app 模块 resources/mybatis/mapper/OutboxMapper.xml
 */
@Mapper
public interface IMqOutboxDao {

    int insert(MqOutbox mqOutbox);

    /**
     * 扫描待重发的 PENDING 行：只取 {@code update_time <= NOW() - timeoutSeconds} 的，
     * 给事务提交后的首次同步发送留出回旋，避免「刚提交就被扫描重发一遍」。
     */
    List<MqOutbox> selectPendingList(@Param("timeoutSeconds") int timeoutSeconds, @Param("limit") int limit);

    int markSentBatch(@Param("ids") List<Long> ids);

    /** 按 msgId 标记已发送（条件 state=0，重复标记幂等） */
    int markSentByMsgId(@Param("msgId") String msgId);

    int markDeadBatch(@Param("ids") List<Long> ids);

    int incrementRetryBatch(@Param("ids") List<Long> ids);
}
