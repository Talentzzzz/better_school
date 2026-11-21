package com.betterschool.infrastructure.ws;

import com.betterschool.infrastructure.ws.wsframe.WsFrame;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 连接枢纽（infrastructure/ws 技术组件的对外 API，同 MQ 生产者待遇）：
 * 建连/入站/断连的连接登记 + 按用户推送一帧。
 * 并发写保护装饰、存活登记（register/refresh/unregister）、连接注册表占位互踢、
 * 多实例路由表的注册/续期/CAS 注销全部封装在组件内。
 * <p>代际 token CAS 注销语义：路由条目 token = {@code 端点标识|sessionId}，
 * 注销/续期只对 token（或端点前缀）仍匹配的条目生效——同实例接管、跨实例迁移场景下
 * 迟到的断连不会误删/误续新连接的条目。token 的构造与存放是组件内部细节，
 * 接口只交接 session 本身。
 * <p>业务侧不直接注入本组件——业务经本域仓储方法用推送（如 chat 域
 * IChatRepository.pushMessage/pushRead/pushRecall，只传本域推送事件），仓储实现组装帧后委托本枢纽
 */
public interface IWsConnectionHub {

    /** 建连登记：装饰 session 并写入握手属性 → 心跳跟踪 → 连接注册表占位（占位即互踢旧连接）→ 多实例形态下注册路由条目 */
    void register(String userId, WebSocketSession session);

    /** 任意入站帧：刷新心跳活跃时间，多实例形态下按代际 token 续期路由条目 TTL */
    void renew(String userId, WebSocketSession session);

    /** 断连注销：心跳去跟踪 → 两参连接注销（迟到 close 不误删新连接）→ 多实例形态下按代际 token CAS 注销路由条目 */
    void revoke(String userId, WebSocketSession session);

    /**
     * 向指定用户推送一帧：目标用户本机在线即推，多实例形态下经路由表/定向频道转发目标实例；
     * 不在线或推送失败静默放弃（漏推由补拉兜底）
     */
    void push(String userId, WsFrame<?> frame);
}
