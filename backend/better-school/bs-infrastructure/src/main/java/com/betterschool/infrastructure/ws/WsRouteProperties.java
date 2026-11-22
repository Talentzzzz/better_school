package com.betterschool.infrastructure.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WS 多实例推送路由配置（Redis 路由表 + pub/sub 定向频道）：
 * self-endpoint 留空 = 单机形态（路由不注册、推送只走本地，测试与单实例部署零感知）；
 * 多实例部署各实例配自己的端点标识（同时作为本实例 pub/sub 频道后缀与路由「指向自己」判定）。
 * 推送是加速路径：无论 miss 还是发布失败都放弃本次推送，漏推由增量补拉兜底
 */
@Component
public class WsRouteProperties {

    /** 本实例的端点标识（如 http://10.0.0.5:8080）：路由 value 前缀 + 本实例频道后缀，留空 = 路由功能关闭 */
    private final String selfEndpoint;

    public WsRouteProperties(
            @Value("${better-school.ws.route.self-endpoint:}") String selfEndpoint) {
        this.selfEndpoint = selfEndpoint == null ? "" : selfEndpoint.trim();
    }

    /** 路由功能开关：self-endpoint 配置了才算启用 */
    public boolean enabled() {
        return !selfEndpoint.isBlank();
    }

    public String getSelfEndpoint() {
        return selfEndpoint;
    }
}
