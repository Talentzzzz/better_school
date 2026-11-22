package com.betterschool.infrastructure.ws;

import com.betterschool.infrastructure.ws.wsframe.WsFrame;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 技术组件的对外门面（infrastructure/ws 唯一公共 API）：
 * 连接生命周期 + 按用户推送 + 握手票据，一个契约，三类消费方各取所需——
 * <ul>
   *   <li>连接层（trigger WsHandler）：register / refresh / clear / push</li>
 *   <li>握手鉴权（trigger 握手拦截器 / 发票 controller）：createTicket / validateAndConsume</li>
 *   <li>业务出站（adapter 域仓储实现）：push</li>
 * </ul>
 * 并发写保护装饰、心跳判死、互踢、多实例路由表的注册/续期/CAS 注销与跨实例定向投递、票据原子取删
 * 全部封装在组件内（实现 = WsService 门面 + 包内协作方
 * WsLocalSessions / IWsRouter / IWsSender / IWsTicketRepository）。
 * <p>代际 token CAS 语义：路由条目 token = {@code 实例 URL|sessionId}，
 * 注销/续期只对 token（或 URL 前缀）仍匹配的条目生效——同实例接管、跨实例迁移场景下
 * 迟到的断连不会误删/误续新连接的条目；token 的构造与存放是组件内部细节，
 * 接口只交接 session 本身。
 * <p>业务侧不直接注入本组件——业务经本域仓储方法推送（如 chat 域
 * IChatRepository.pushMessage/pushRead/pushRecall，只传本域推送事件），仓储实现组装帧后委托本组件
 */
public interface IWsService {

    /** 建连登记：装饰 session 并写入握手属性 → 心跳跟踪 + 互踢占位（占位即互踢旧连接）→ 多实例形态下注册路由条目 */
    void register(String userId, WebSocketSession session);

    /** 任意入站帧：刷新心跳活跃时间，多实例形态下按代际 token 续期路由条目 TTL */
    void refresh(String userId, WebSocketSession session);

    /** 断连注销：心跳去跟踪 → 两参连接注销（迟到 close 不误删新连接）→ 多实例形态下按代际 token CAS 注销路由条目 */
    void clear(String userId, WebSocketSession session);

    /**
     * 向指定用户推送一帧：目标用户本机在线即推，不在线时多实例形态下由路由表定向转发目标实例；
     * 不在线或推送失败静默放弃（漏推由补拉兜底）
     */
    void push(String userId, WsFrame<?> frame);

    /** 签发一张新票据：绑定当前用户，60s 有效（spec §3） */
    String createTicket(String userId);

    /** 原子取删校验：有效返回绑定的 userId 并立刻作废，无效（不存在/已用/过期）返回 null（spec §3） */
    String validateAndConsume(String ticket);
}
