package com.betterschool.api;

import com.betterschool.api.dto.NotificationBroadcastRequestDTO;
import com.betterschool.api.dto.NotificationReadRequestDTO;
import com.betterschool.api.dto.NotificationResponseDTO;
import com.betterschool.api.dto.NotificationUnreadCountResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

/**
 * 站内通知服务契约：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface INotificationService {

    /** scope 为 null 时不过滤（全部范围） */
    Response<PageResult<NotificationResponseDTO>> listNotifications(int page, int pageSize, String scope);

    /** 未读数 = 计数器 − 水位（按 scope 独立计算） */
    Response<NotificationUnreadCountResponseDTO> unreadCount(String scope);

    /** 按范围推已读水位，取代原 read-all（逐行 is_read 已退休） */
    Response<Void> markRead(NotificationReadRequestDTO request);

    /** 管理员公告广播（outbox + MQ → 全用户落库） */
    Response<Void> broadcast(NotificationBroadcastRequestDTO request);
}
