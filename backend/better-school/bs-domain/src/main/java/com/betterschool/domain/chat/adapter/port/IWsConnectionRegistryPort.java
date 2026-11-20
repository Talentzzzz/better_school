package com.betterschool.domain.chat.adapter.port;

import org.springframework.web.socket.WebSocketSession;

/**
 * WS 连接登记端口：连接处理器在建连/入站/断连时把 session 交给 infra 的 WS 推送适配器登记，
 * trigger 侧不再自己持有注册表——并发写保护装饰、心跳跟踪（track/touch/untrack）、
 * 连接注册表占位互踢、多实例路由表的注册/续期/注销全部封装在适配器内。
 * <p>代际 token CAS 注销语义随端口下沉：路由条目 token = {@code 端点标识|sessionId}，
 * 注销/续期只对 token（或端点前缀）仍匹配的条目生效——同实例接管、跨实例迁移场景下
 * 迟到的断连不会误删/误续新连接的条目。token 的构造与存放是适配器内部细节，
 * 端口只交接 session 本身。
 */
public interface IWsConnectionRegistryPort {

    /** 建连登记：装饰 session 并写入握手属性 → 心跳跟踪 → 连接注册表占位（占位即互踢旧连接）→ 多实例形态下注册路由条目 */
    void register(String userId, WebSocketSession session);

    /** 任意入站帧：刷新心跳活跃时间，多实例形态下按代际 token 续期路由条目 TTL */
    void renew(String userId, WebSocketSession session);

    /** 断连注销：心跳去跟踪 → 两参连接注销（迟到 close 不误删新连接）→ 多实例形态下按代际 token CAS 注销路由条目 */
    void revoke(String userId, WebSocketSession session);
}
