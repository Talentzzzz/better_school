package com.betterschool.infrastructure.ws;

import com.betterschool.infrastructure.ws.wsframe.WsFrame;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 技术组件门面实现：{@link IWsService} 六个方法的机器本体（infrastructure/ws 技术组件）。
 * 纯编排——各协作方各管一段，细节不出各自组件：
 * <ul>
 *   <li>本地状态（互踢表 + 心跳判死）→ {@link WsLocalSessions}</li>
 *   <li>多实例路由（记账 bind/renew/unbind + 跨实例定向投递 forward，token 构造/CAS/pub-sub 运输在其内）→ {@link IWsRouter}</li>
 *   <li>帧发送（装饰/序列化/互踢/关闭）→ {@link IWsSender}</li>
 *   <li>握手票据（60s 一次性，原子取删）→ {@link IWsTicketRepository}</li>
 * </ul>
 * 并发写保护装饰是组件内部细节——登记时装饰入册、注销时由本地账本取回装饰件，
 * 装饰件不出 ws 组件、不落握手属性；路由条目的代际 token 同样不落 session attributes
 */
@Component
@RequiredArgsConstructor
public class WsService implements IWsService {

    private final WsLocalSessions localSessions;
    private final IWsRouter router;
    private final IWsSender frameSender;
    private final IWsTicketRepository ticketRepository;

    // ---- 连接生命周期 ----

    @Override
    public void register(String userId, WebSocketSession session) {
        // 本地登记：装饰 + 心跳卡 + 互踢占位（占位即互踢旧连接），装饰件不出组件
        localSessions.register(userId, session);
        // 多实例形态下注册路由条目；单机形态组件内自跳过
        router.bind(userId, session.getId());
    }

    @Override
    public void refresh(String userId, WebSocketSession session) {
        // 任何入站帧（含 PING 与解析失败的帧）都刷新心跳活跃时间
        localSessions.refresh(session);
        // 多实例形态下按代际 token 续期路由条目 TTL；单机形态组件内自跳过
        router.renew(userId, session.getId());
    }

    @Override
    public void clear(String userId, WebSocketSession session) {
        // 本地注销：心跳去卡 → 两参互踢注销（被互踢旧连接迟到 close 不误删新连接），装饰件由本地账本取回
        localSessions.revoke(userId, session);
        // 多实例形态下按代际 token CAS 注销路由条目；单机形态组件内自跳过
        router.unbind(userId, session.getId());
    }

    // ---- 推送出站 ----

    @Override
    public void push(String userId, WsFrame<?> frame) {
        // 本机在线即推；不在线/连接已死时多实例形态下移交路由表定向转发目标实例（单机形态组件内自跳过）
        WebSocketSession session = localSessions.get(userId);
        if (session != null && session.isOpen()) {
            frameSender.push(session, frame);
            return;
        }
        router.forward(userId, frame);
    }

    // ---- 握手票据（委托票据存储） ----

    @Override
    public String createTicket(String userId) {
        return ticketRepository.createTicket(userId);
    }

    @Override
    public String validateAndConsume(String ticket) {
        return ticketRepository.validateAndConsume(ticket);
    }
}
