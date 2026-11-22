package com.betterschool.infrastructure.ws;

import com.betterschool.infrastructure.ws.wsframe.WsFrame;

/**
 * WS 多实例路由契约：「用户现在连在哪个实例」的账本 + 账本之上的定向投递。
 * 条目 token = {@code 实例 URL|sessionId}（代际标识），构造与比对语义都属实现细节；
 * 单机形态（未配置实例 URL）整体惰性关闭——bind/renew/unbind/forward 直接跳过，调用方无须判形态；
 * 漏推合法，由客户端按 (roomId, seq) 判重的增量补拉兜底。
 * 实现：{@link WsRouter}（Redis 记账 + pub/sub 定向频道运输）
 */
public interface IWsRouter {

    /** 建连记账：写入条目，带 TTL（入站帧心跳续期） */
    void bind(String userId, String sessionId);

    /** 入站帧续期：任意入站帧刷 TTL；按代际 token 防误续接管后的新条目 */
    void renew(String userId, String sessionId);

    /** 断连注销：按代际 token CAS——被互踢/接管后迟到的 close 不误删新连接的条目 */
    void unbind(String userId, String sessionId);

    /** 跨实例定向转发：目标实例不在线/发布失败/本地 miss 一律清理路由条目后放弃（漏推由补拉兜底） */
    void forward(String userId, WsFrame<?> frame);
}
