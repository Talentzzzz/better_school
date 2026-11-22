package com.betterschool.infrastructure.ws;

import com.betterschool.infrastructure.ws.wsframe.WsFrame;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 帧发送契约：一条 session 出站侧的唯一物理收口——发送（push/pushJson）、互踢、关闭全是出站动作，
 * decorate 是出站通道的并发写保护设置。漏推合法，客户端按 (roomId, seq) 判重 + 增量补拉收敛。
 * 实现：{@link WsSender}
 */
public interface IWsSender {

    /** 并发写保护包装：本地登记时组件内调用一次，此后推送/关闭一律用装饰后的 session */
    WebSocketSession decorate(WebSocketSession session);

    /** 推送一帧：JSON 序列化后写出；失败静默放弃（漏推合法，客户端兜底收敛） */
    void push(WebSocketSession session, WsFrame<?> frame);

    /** 跨实例转发件直发：直接发送已序列化的线上 JSON（免 反序列化→再序列化 一轮） */
    void pushJson(WebSocketSession session, String frameJson);

    /** 互踢：先发 KICK 帧让客户端提示重新登录，随后 close(4001) 阻断自动重连 */
    void kick(WebSocketSession session);

    /** 静默关闭：close 异常吞掉（断连路径不再抛） */
    void closeQuietly(WebSocketSession session, CloseStatus status);
}
