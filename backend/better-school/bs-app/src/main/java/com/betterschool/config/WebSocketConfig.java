package com.betterschool.config;

import com.betterschool.trigger.ws.ChatWsHandler;
import com.betterschool.trigger.ws.ChatWsHandshakeInterceptor;
import com.betterschool.trigger.ws.ChatWsProtocol;
import com.betterschool.trigger.ws.ReviewWsHandler;
import com.betterschool.trigger.ws.ReviewWsHandshakeInterceptor;
import com.betterschool.trigger.ws.ReviewWsProtocol;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;

/**
 * WS 端点注册：Spring 原生裸 WebSocket，握手鉴权走一次性票据拦截器。
 * chat（票 04）与 review（票 10）两个独立端点——骨架共享（连接层 support），
 * 端点常量与帧类型各归各的协议类，互不复用
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWsHandler chatWsHandler;
    private final ChatWsHandshakeInterceptor chatWsHandshakeInterceptor;
    private final ReviewWsHandler reviewWsHandler;
    private final ReviewWsHandshakeInterceptor reviewWsHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWsHandler, ChatWsProtocol.ENDPOINT)
                .addInterceptors(chatWsHandshakeInterceptor);
        registry.addHandler(reviewWsHandler, ReviewWsProtocol.ENDPOINT)
                .addInterceptors(reviewWsHandshakeInterceptor);
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
