package com.betterschool.unit.outbox;

import com.betterschool.infrastructure.dao.outbox.IOutboxDao;
import com.betterschool.infrastructure.dao.outbox.po.OutboxPO;
import com.betterschool.infrastructure.outbox.OutboxMessageSender;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 票 02：outbox 落库时的两个延迟字段互斥——{@code delay_ms} 有值时 {@code delay_level} 必须落 0。
 * 为什么值得单测：发送侧靠这两列判分支，两列同时非空就没有「听谁的」这个答案，
 * 只能靠落库时挡住（而不是让发送侧去猜）。
 */
@DisplayName("outbox 落库的延迟字段互斥（票 02）")
class OutboxMessageStoreTest {

    private static final String TOPIC = "bs-inventory-merge";
    private static final Object PAYLOAD = java.util.Map.of("segment", "QUOTA");

    private IOutboxDao outboxDao;
    private OutboxMessageStore store;

    @BeforeEach
    void setUp() {
        outboxDao = mock(IOutboxDao.class);
        store = new OutboxMessageStore(outboxDao, mock(OutboxMessageSender.class), new ObjectMapper());
        // save() 会注册 afterCommit 同步器，需要有活动的事务同步上下文（这里只验落库字段，不跑提交动作）
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("delayMs 有值：delay_ms 落 2000、delay_level 归 0")
    void delayMs_winsAndResetsDelayLevel() {
        store.save(TOPIC, PAYLOAD, 0, 2000);

        OutboxPO saved = capturedInsert();
        assertThat(saved.getDelayMs()).isEqualTo(2000);
        assertThat(saved.getDelayLevel()).as("互斥：有毫秒延迟时级别必须落 0").isZero();
        assertThat(saved.getMsgId()).as("幂等键 uk_msg_id 仍由存储层生成").isNotBlank();
    }

    @Test
    @DisplayName("只给 delayLevel：delay_ms 落 NULL，既有事件行为不变")
    void delayLevelOnly_keepsDelayMsNull() {
        store.save(TOPIC, PAYLOAD, 1);

        OutboxPO saved = capturedInsert();
        assertThat(saved.getDelayLevel()).isEqualTo(1);
        assertThat(saved.getDelayMs()).isNull();
    }

    @Test
    @DisplayName("同时给 delayLevel 与 delayMs：拒绝落库（不猜谁优先）")
    void bothDelays_rejected() {
        assertThatThrownBy(() -> store.save(TOPIC, PAYLOAD, 1, 2000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("互斥");
        verify(outboxDao, never()).insert(any());
    }

    @Test
    @DisplayName("delayMs 非正：拒绝落库（0 毫秒定时消息没有意义，走不延迟重载）")
    void nonPositiveDelayMs_rejected() {
        assertThatThrownBy(() -> store.save(TOPIC, PAYLOAD, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(outboxDao, never()).insert(any());
    }

    private OutboxPO capturedInsert() {
        ArgumentCaptor<OutboxPO> captor = ArgumentCaptor.forClass(OutboxPO.class);
        verify(outboxDao).insert(captor.capture());
        return captor.getValue();
    }
}
