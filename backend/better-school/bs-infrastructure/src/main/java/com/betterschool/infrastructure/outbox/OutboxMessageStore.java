package com.betterschool.infrastructure.outbox;

import com.betterschool.infrastructure.dao.outbox.IOutboxDao;
import com.betterschool.infrastructure.dao.outbox.po.OutboxPO;
import com.betterschool.types.enums.OutboxStatus;
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
 * outbox 消息存储（形状照单体）：事务内插入消息记录（须被业务事务包裹），
 * 事务提交后立即异步发送一次。仓储在业务动作的同事务内调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageStore {

    private final IOutboxDao outboxDao;
    private final OutboxMessageSender messageSender;
    private final ObjectMapper objectMapper;

    /** 不延迟（默认）：points / raffle 的既有事件都走这条重载 */
    public void save(String destination, Object payload) {
        save(destination, payload, 0, null);
    }

    /**
     * 延迟级别随消息一起落库：首次异步发送与扫描重发都读这一列，
     * 所以「1 秒后合并」不会因为兜底重发变成立刻合并。
     *
     * @param delayLevel RocketMQ 延迟等级；0=不延迟，1=1 秒
     */
    public void save(String destination, Object payload, int delayLevel) {
        save(destination, payload, delayLevel, null);
    }

    /**
     * 毫秒级相对延迟（RocketMQ 定时消息）：给不了延迟等级那种「任意毫秒」的场景用，
     * 例如库存合并要「发出后 2 秒」而不是某个固定档位。
     * <p>
     * 两个延迟字段**互斥**：有 {@code delayMs} 时 {@code delay_level} 落 0。
     * 否则发送侧就要回答「两个都有听谁的」，而这个答案迟早会和落库的那一行对不上。
     *
     * @param delayLevel RocketMQ 延迟等级；与 {@code delayMs} 互斥
     * @param delayMs    毫秒级相对延迟（从发送时刻起算）；null=不延迟
     */
    public void save(String destination, Object payload, int delayLevel, Integer delayMs) {
        if (delayMs != null && (delayMs <= 0 || delayLevel > 0)) {
            throw new IllegalArgumentException(
                    "delayMs 必须为正且与 delayLevel 互斥：delayLevel=" + delayLevel + ", delayMs=" + delayMs);
        }
        OutboxPO outboxPO = insertPending(destination, payload, delayLevel, delayMs);
        // 事务提交后立即异步发送一次（降延迟）；事务回滚时 afterCommit 不触发，记录随回滚消失
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messageSender.sendAsync(outboxPO);
            }
        });
    }

    /**
     * 独立落库发送（**不经业务事务**）：给无事务模板的旁路 job 用（如对账轮告警）。
     * 单行 insert 自带隐式事务，插入后立即异步首发一次；发送失败由 {@code outboxScan}
     * 扫描兜底重发（行保持 PENDING），消费侧按业务幂等键吸收重投。
     * 没有回滚语义——调用方在插入前自行保证「这条消息本来就该发」。
     */
    public void saveStandalone(String destination, Object payload) {
        OutboxPO outboxPO = insertPending(destination, payload, 0, null);
        messageSender.sendAsync(outboxPO);
    }

    /** 构造 PENDING 行并插入（无发送动作）：save 走 afterCommit 首发，saveStandalone 直发 */
    private OutboxPO insertPending(String destination, Object payload, int delayLevel, Integer delayMs) {
        OutboxPO outboxPO = new OutboxPO();
        outboxPO.setMsgId(UUID.randomUUID().toString());
        outboxPO.setDestination(destination);
        outboxPO.setPayload(toJson(payload));
        outboxPO.setDelayLevel(delayMs == null ? delayLevel : 0);
        outboxPO.setDelayMs(delayMs);
        outboxPO.setState(OutboxStatus.PENDING.getCode());
        outboxPO.setRetryCount(0);
        outboxPO.setCreateTime(LocalDateTime.now());
        outboxPO.setUpdateTime(LocalDateTime.now());
        outboxDao.insert(outboxPO);
        return outboxPO;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox 消息序列化失败", e);
        }
    }
}
