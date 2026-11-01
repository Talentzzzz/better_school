package com.betterschool.config;

import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.apache.rocketmq.spring.support.RocketMQMessageListenerContainerRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 接管 rocketmq-spring 的容器注册器（virtual-thread spec §4.1，票 14）：默认实现带
 * {@code @ConditionalOnMissingBean}，这里提供自己的 bean 即整体接管。Bean 声明成接口类型是为了让那个
 * 条件按类型匹配到。
 */
@Configuration
public class RocketMQConsumerConfig {

    @Bean
    public RocketMQMessageListenerContainerRegistrar rocketMQMessageListenerContainerRegistrar(
            RocketMQMessageConverter rocketMQMessageConverter,
            ConfigurableEnvironment environment,
            RocketMQProperties rocketMQProperties) {
        return new VirtualThreadRocketMQListenerContainerRegistrar(
                rocketMQMessageConverter, environment, rocketMQProperties);
    }
}
