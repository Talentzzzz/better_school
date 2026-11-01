package com.betterschool.infrastructure.outbox;

import com.betterschool.infrastructure.dao.outbox.IOutboxDao;
import com.betterschool.infrastructure.dao.outbox.po.OutboxPO;
import com.betterschool.infrastructure.mq.OutboxMessageSender;
import com.betterschool.types.enums.OutboxStatus;
import com.betterschool.types.exception.FrameworkException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * outbox 消息存储（基础设施内部组件）：事务内插入消息记录（须被业务事务包裹），
 * 事务提交后立即异步发送一次。各域 repository 在业务动作的同事务内调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageStore {

    private final IOutboxDao outboxDao;
    private final OutboxMessageSender messageSender;
    private final ObjectMapper objectMapper;

    public void save(String destination, Object payload) {
        OutboxPO outboxPO = new OutboxPO();
        outboxPO.setMsgId(UUID.randomUUID().toString());
        outboxPO.setDestination(destination);
        outboxPO.setPayload(toJson(payload));
        outboxPO.setState(OutboxStatus.PENDING.getCode());
        outboxPO.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now();
        outboxPO.setCreateTime(now);
        outboxPO.setUpdateTime(now);
        outboxDao.insert(outboxPO);
        // 事务提交后立即异步发送一次（降延迟）；事务回滚时 afterCommit 不触发，记录随回滚消失
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messageSender.sendAsync(outboxPO);
            }
        });
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new FrameworkException("outbox 消息序列化失败", e);
        }
    }
}
