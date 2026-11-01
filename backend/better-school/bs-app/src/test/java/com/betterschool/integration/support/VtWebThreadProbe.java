package com.betterschool.integration.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Web seam 断言的测试探针（spec §5.2 #1，票 12）：test classpath 里的 @Configuration 会被组件扫描
 * 收进上下文，注册一个最高优先级的 OncePerRequestFilter，记录 handler 线程是不是虚拟线程。
 * <p>
 * 防的是 {@code spring.threads.virtual.enabled} 被翻回（spec §5.3 退回路径 #1）：断言红 = 开关被关。
 * 只在测试侧存在，运行时零开销。
 */
@Configuration
public class VtWebThreadProbe {

    private static volatile boolean sawHandler;
    private static volatile boolean handlerWasVirtual;

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> vtWebThreadProbeFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                sawHandler = true;
                handlerWasVirtual = Thread.currentThread().isVirtual();
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        // 最高优先级：跑在安全链之前，任何端点（含 401/404）都先过这里——seam 断言不挑业务端点
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** 自上次 {@link #reset()} 起是否有请求经过探针 */
    public static boolean sawHandler() {
        return sawHandler;
    }

    /** 最近一个经过探针的 handler 线程是否为虚拟线程 */
    public static boolean handlerWasVirtual() {
        return handlerWasVirtual;
    }

    public static void reset() {
        sawHandler = false;
        handlerWasVirtual = false;
    }
}
