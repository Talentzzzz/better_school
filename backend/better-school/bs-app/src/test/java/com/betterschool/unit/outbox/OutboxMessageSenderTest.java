package com.betterschool.unit.outbox;

import com.betterschool.infrastructure.dao.outbox.IOutboxDao;
import com.betterschool.infrastructure.dao.outbox.po.OutboxPO;
import com.betterschool.infrastructure.outbox.OutboxMessageSender;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 票 02：outbox 发送的三条分支 + 扫描重发的「延迟已过期」特例。
 * <p>
 * 用 mock 的 producer / template 而不是真 broker——这里要断的是**选了哪条发送路径、报文长什么样**，
 * 真机投递时延由票 05 的集成测试断言（测试栈本来就没有 broker）。
 */
@DisplayName("outbox 发送分支（票 02）")
class OutboxMessageSenderTest {

    private static final String TOPIC = "bs-inventory-merge";
    private static final String PAYLOAD = "{\"segment\":\"QUOTA\",\"invId\":7,\"bucketId\":0}";
    private static final int SEND_TIMEOUT = 3000;

    private IOutboxDao outboxDao;
    private RocketMQTemplate rocketMQTemplate;
    private DefaultMQProducer producer;
    private OutboxMessageSender sender;

    @BeforeEach
    void setUp() {
        outboxDao = mock(IOutboxDao.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        producer = mock(DefaultMQProducer.class);
        when(rocketMQTemplate.getProducer()).thenReturn(producer);
        when(rocketMQTemplate.getCharset()).thenReturn("UTF-8");
        when(producer.getSendMsgTimeout()).thenReturn(SEND_TIMEOUT);
        sender = new OutboxMessageSender(outboxDao, rocketMQTemplate, 60);
    }

    @Test
    @DisplayName("分支一：delayMs 非空 → producer 直发定时消息，topic/tag/字节与模板转换一致")
    void delayMs_goesThroughProducerWithTimerProperty() throws Exception {
        sender.sendAsync(outbox("bs-inventory-merge:merge-tag", 0, 2000));

        ArgumentCaptor<org.apache.rocketmq.common.message.Message> captor =
                ArgumentCaptor.forClass(org.apache.rocketmq.common.message.Message.class);
        verify(producer).send(captor.capture(), any(SendCallback.class), eq((long) SEND_TIMEOUT));

        org.apache.rocketmq.common.message.Message message = captor.getValue();
        assertThat(message.getDelayTimeMs()).as("定时消息靠这个属性生效").isEqualTo(2000);
        assertThat(message.getTopic()).isEqualTo(TOPIC);
        assertThat(message.getTags()).as("destination 的 tag 段照模板规则切出来").isEqualTo("merge-tag");
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .as("String 载荷取原字节，不做二次 JSON 编码").isEqualTo(PAYLOAD);
        verify(rocketMQTemplate, never()).asyncSend(anyString(), any(Message.class), any(SendCallback.class),
                anyLong(), anyInt());
        verify(rocketMQTemplate, never()).asyncSend(anyString(), any(Object.class), any(SendCallback.class));
    }

    @Test
    @DisplayName("分支二：只有 delayLevel → 模板五参 asyncSend（异步带延迟级别）")
    void delayLevel_goesThroughTemplateAsyncSend() throws Exception {
        sender.sendAsync(outbox(TOPIC, 1, null));

        verify(rocketMQTemplate).asyncSend(eq(TOPIC), any(Message.class), any(SendCallback.class),
                eq((long) SEND_TIMEOUT), eq(1));
        verify(producer, never()).send(any(org.apache.rocketmq.common.message.Message.class),
                any(SendCallback.class), anyLong());
    }

    @Test
    @DisplayName("分支三：两个延迟字段都没有 → 模板两参 asyncSend（不延迟）")
    void noDelay_goesThroughPlainAsyncSend() throws Exception {
        sender.sendAsync(outbox(TOPIC, 0, null));

        verify(rocketMQTemplate).asyncSend(eq(TOPIC), eq(PAYLOAD), any(SendCallback.class));
        verify(producer, never()).send(any(org.apache.rocketmq.common.message.Message.class),
                any(SendCallback.class), anyLong());
    }

    @Test
    @DisplayName("两列同时非空（只可能是绕过 save 的写入）：delayMs 优先，仍走定时消息")
    void bothDelays_timerWins() throws Exception {
        sender.sendAsync(outbox(TOPIC, 1, 2000));

        ArgumentCaptor<org.apache.rocketmq.common.message.Message> captor =
                ArgumentCaptor.forClass(org.apache.rocketmq.common.message.Message.class);
        verify(producer).send(captor.capture(), any(SendCallback.class), eq((long) SEND_TIMEOUT));
        assertThat(captor.getValue().getDelayTimeMs()).isEqualTo(2000);
        verify(rocketMQTemplate, never()).asyncSend(anyString(), any(Message.class), any(SendCallback.class),
                anyLong(), anyInt());
    }

    @Test
    @DisplayName("扫描重发：delayMs 行不再定时（那个时刻早过期了），直接普通发送")
    void scanPending_resendsDelayMsRowsImmediately() throws Exception {
        when(outboxDao.selectPendingList(60, 100))
                .thenReturn(List.of(outbox(TOPIC, 0, 2000)));

        sender.scanPending("");

        verify(rocketMQTemplate).asyncSend(eq(TOPIC), eq(PAYLOAD), any(SendCallback.class));
        verify(producer, never()).send(any(org.apache.rocketmq.common.message.Message.class),
                any(SendCallback.class), anyLong());
    }

    @Test
    @DisplayName("扫描重发：delayLevel 行仍按列里的级别发（ADR-0011 的既有语义不变）")
    void scanPending_keepsDelayLevelOnResend() throws Exception {
        when(outboxDao.selectPendingList(60, 100))
                .thenReturn(List.of(outbox(TOPIC, 1, null)));

        sender.scanPending("");

        verify(rocketMQTemplate).asyncSend(eq(TOPIC), any(Message.class), any(SendCallback.class),
                eq((long) SEND_TIMEOUT), eq(1));
    }

    private OutboxPO outbox(String destination, int delayLevel, Integer delayMs) {
        OutboxPO outboxPO = new OutboxPO();
        outboxPO.setId(1L);
        outboxPO.setMsgId("msg-1");
        outboxPO.setDestination(destination);
        outboxPO.setPayload(PAYLOAD);
        outboxPO.setDelayLevel(delayLevel);
        outboxPO.setDelayMs(delayMs);
        outboxPO.setRetryCount(0);
        return outboxPO;
    }
}
