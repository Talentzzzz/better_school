package com.betterschool.domain.chat.adapter.port;

/**
 * WS 连接路由表（Redis，多实例推送路由）：uid → 本实例端点标识。
 * token 口径 = {@code 端点标识|sessionId}——带上会话 id 是为了「同实例接管」也能区分：
 * 新连接接管注册表后，旧连接的 CAS 注销不会误删新连接的条目（同端点不同会话）
 */
public interface IWsRoutePort {

    /** 连接建立：注册 uid → token（端点标识|会话 id），带 TTL；同 userId 后写者胜（与互踢「占位即接管」同口径） */
    void register(String userId, String token);

    /** 心跳续期：仅当当前值等于 token 才续 TTL（连接已迁走/断开则不误续别人的条目） */
    void renew(String userId, String token);

    /** 本连接注销：仅当当前值等于 token 才删除（防误删接管后的新条目） */
    void revoke(String userId, String token);

    /** 推送 miss 清理：仅当当前值指向该端点才删除（路由过期兜底；由推送路由侧调用） */
    void revokeByEndpoint(String userId, String endpoint);

    /** 查路由：返回端点标识（pub/sub 定向频道后缀 + 「指向自己」判定）；无路由返回 null */
    String lookupEndpoint(String userId);
}
