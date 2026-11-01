package com.betterschool.trigger.listener;

import com.betterschool.types.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 用户注册消息演示消费方：真实业务消费（注册通知、ES 同步等）在后续域接入时实现，
 * 届时必须按 msg_id 做幂等（outbox 扫描兜底可能重复发送）
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = Constants.USER_REGISTER_TOPIC, consumerGroup = "bs-user-register-group")
public class UserRegisterConsumer implements RocketMQListener<String> {

    @Override
    public void onMessage(String payload) {
        log.info("[UserRegisterConsumer] 收到注册消息：{}", payload);
    }
}
