package com.betterschool.trigger.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WS 多实例推送路由配置（Redis 路由表 + HTTP 转发）：
 * self-endpoint 留空 = 单机形态（路由不注册、推送只走本地，测试与单实例部署零感知）；
 * 多实例部署各实例配自己的对内可达端点 + 共享的内部密钥（内部转发接口 fail-closed）。
 * 转发是加速路径：无论 miss 还是调用失败都放弃本次推送，漏推由增量补拉兜底
 */
@Component
public class WsRouteProperties {

    /** 本实例对内可达的转发端点（如 http://10.0.0.5:8080），留空 = 路由功能关闭 */
    private final String selfEndpoint;

    /** 实例间内部转发接口共享密钥（X-Internal-Token） */
    private final String internalToken;

    public WsRouteProperties(
            @Value("${better-school.ws.route.self-endpoint:}") String selfEndpoint,
            @Value("${better-school.ws.route.internal-token:}") String internalToken) {
        this.selfEndpoint = selfEndpoint == null ? "" : selfEndpoint.trim();
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /** 路由功能开关：self-endpoint 配置了才算启用 */
    public boolean enabled() {
        return !selfEndpoint.isBlank();
    }

    public String getSelfEndpoint() {
        return selfEndpoint;
    }

    public String getInternalToken() {
        return internalToken;
    }
}
