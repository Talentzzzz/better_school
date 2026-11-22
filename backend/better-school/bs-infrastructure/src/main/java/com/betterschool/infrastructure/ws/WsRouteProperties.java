package com.betterschool.infrastructure.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WS 多实例推送路由配置（Redis 路由表 + pub/sub 定向频道）：
 * instance-url 留空 = 单机形态（路由不注册、推送只走本地，测试与单实例部署零感知）；
 * 多实例部署各实例配自己的实例 URL（同时作为本实例 pub/sub 频道后缀与路由「指向自己」判定）。
 * 推送是加速路径：无论 miss 还是发布失败都放弃本次推送，漏推由增量补拉兜底
 */
@Component
public class WsRouteProperties {

    /** 本实例的 URL（如 http://10.0.0.5:8080）：路由 value 前缀 + 本实例频道后缀，留空 = 路由功能关闭 */
    private final String instanceUrl;

    public WsRouteProperties(
            @Value("${better-school.ws.route.instance-url:}") String instanceUrl) {
        this.instanceUrl = instanceUrl == null ? "" : instanceUrl.trim();
    }

    /** 路由功能开关：instance-url 配置了才算启用 */
    public boolean enabled() {
        return !instanceUrl.isBlank();
    }

    public String getInstanceUrl() {
        return instanceUrl;
    }
}
