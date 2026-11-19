package com.betterschool.domain.chat.adapter.port;

import java.util.function.Consumer;

/**
 * WS 跨实例推送 pub/sub 定向频道（Redis pub/sub，多实例推送路由）：
 * 每个实例订阅以自身端点标识命名的频道，发布方按路由查到的端点精确发布——只有持有连接的那台会收到。
 * fire-and-forget：无回执、无持久化、订阅方不在线消息即丢，漏推由客户端 (roomId, seq) 增量补拉兜底；
 * 消息体走 JSON 字符串（协议信封的序列化归 trigger 层，本端口只管传输）
 */
public interface IWsPushBroadcastPort {

    /** 向目标实例的定向频道发布一帧（JSON 文本）；返回接收的订阅者数，0 = 目标实例不在线（路由已过期） */
    long publish(String endpoint, String messageJson);

    /** 订阅本实例的定向频道（启动时调用一次）；返回监听器 id，退订用 */
    String subscribe(String endpoint, Consumer<String> listener);

    /** 退订本实例的定向频道（endpoint 与 subscribe 时一致） */
    void unsubscribe(String endpoint, String listenerId);
}
