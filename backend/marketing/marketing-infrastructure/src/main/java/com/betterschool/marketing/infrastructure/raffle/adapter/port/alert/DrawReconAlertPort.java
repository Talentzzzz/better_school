package com.betterschool.marketing.infrastructure.raffle.adapter.port.alert;

import com.betterschool.marketing.domain.raffle.adapter.port.alert.IDrawReconAlertPort;
import com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageStore;
import com.betterschool.marketing.types.common.Constants;
import com.betterschool.marketing.types.common.DrawReconAlertMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 对账告警端口的实现：把告警字段翻成 wire 消息 {@link DrawReconAlertMessage}，
 * 经 {@link OutboxMessageStore#saveStandalone} 落 outbox（单行原子写 + 立即异步首发）。
 * <p>
 * 对账服务无事务模板（分层纪律），不走需要业务事务包裹的 {@code save}——
 * 那条路径在无活动事务时注册 afterCommit 同步会直接抛
 * {@code IllegalStateException}；旁路告警用独立落库路径，发送失败由
 * {@code outboxScan} 扫描兜底重发，消费侧按 dedupKey 幂等吸收。
 */
@Component
@RequiredArgsConstructor
public class DrawReconAlertPort implements IDrawReconAlertPort {

    private final OutboxMessageStore outboxMessageStore;

    @Override
    public void sendReconAlert(String roundNo, String checkType, String scopeKey, String bizId,
                               String expectSummary, String actualSummary, String alertText) {
        outboxMessageStore.saveStandalone(Constants.RECON_ALERT_TOPIC,
                new DrawReconAlertMessage(roundNo, checkType, scopeKey, bizId,
                        expectSummary, actualSummary, alertText));
    }
}
