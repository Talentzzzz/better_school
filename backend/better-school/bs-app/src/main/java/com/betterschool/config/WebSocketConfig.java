package com.betterschool.config;

import com.betterschool.trigger.ws.ChatWsHandler;
import com.betterschool.trigger.ws.ChatWsHandshakeInterceptor;
import com.betterschool.trigger.ws.ChatWsProtocol;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;

/**
 * WS 端点注册：Spring 原生裸 WebSocket，握手鉴权走一次性票据拦截器。
 * chat 端点——连接层骨架在 ws/support，
 * 端点常量与帧类型归 ChatWsProtocol。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWsHandler chatWsHandler;
    private final ChatWsHandshakeInterceptor chatWsHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWsHandler, ChatWsProtocol.ENDPOINT)
                .addInterceptors(chatWsHandshakeInterceptor);
    }

    /**
     * 容器层空闲兜底（设计稿 §3.2）：应用层 60s 判死清扫之外，容器 90s 无任何读写也断开
     * （客户端心跳 25s 一次，存活连接不会触发）
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(90_000L);
        return container;
    }
}
