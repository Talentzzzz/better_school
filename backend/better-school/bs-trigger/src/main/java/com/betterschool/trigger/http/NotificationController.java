package com.betterschool.trigger.http;

import com.betterschool.api.INotificationService;
import com.betterschool.api.dto.NotificationBroadcastRequestDTO;
import com.betterschool.api.dto.NotificationReadRequestDTO;
import com.betterschool.api.dto.NotificationResponseDTO;
import com.betterschool.api.dto.NotificationUnreadCountResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内通知 HTTP 入口：列表/未读数/已读需登录（自己可见）；公告广播仅 ADMIN
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class NotificationController implements INotificationService {

    private static final int MAX_PAGE_SIZE = 50;

    /** 通知域服务与 api 契约同名不同层，这里用全限定名避免与 implements 的 api 接口撞名 */
    private final com.betterschool.domain.notification.service.INotificationService notificationService;

    @Override
    @GetMapping("/notifications")
    public Response<PageResult<NotificationResponseDTO>> listNotifications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String scope) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        NotificationScopeVO scopeVO = parseScope(scope);
        String userId = SecurityUtils.currentUserId();
        long total = notificationService.countNotifications(userId, scopeVO);
        List<NotificationEntity> notificationList = notificationService.listNotifications(userId, scopeVO, page, pageSize);
        List<NotificationResponseDTO> dtoList = notificationList.stream().map(this::toNotificationResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/notifications/unread-count")
    public Response<NotificationUnreadCountResponseDTO> unreadCount(@RequestParam String scope) {
        long count = notificationService.unreadCount(SecurityUtils.currentUserId(), requireScope(scope));
        NotificationUnreadCountResponseDTO dto = new NotificationUnreadCountResponseDTO();
        dto.setCount(count);
        return Response.ok(dto);
    }

    @Override
    @PostMapping("/notifications/read")
    public Response<Void> markRead(@Valid @RequestBody NotificationReadRequestDTO request) {
        notificationService.markRead(SecurityUtils.currentUserId(), requireScope(request.getScope()));
        return Response.ok(null);
    }

    @Override
    @PostMapping("/notifications/broadcast")
    public Response<Void> broadcast(@Valid @RequestBody NotificationBroadcastRequestDTO request) {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权限，仅管理员可发布公告");
        }
        notificationService.broadcast(request.getType(), request.getTitle(), request.getContent());
        return Response.ok(null);
    }

    /** 列表的 scope 可省略（不过滤）；unread-count / read 的 scope 必填，空白也按参数错误拒绝 */
    private NotificationScopeVO parseScope(String scope) {
        return scope == null || scope.isBlank() ? null : NotificationScopeVO.from(scope);
    }

    private NotificationScopeVO requireScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "通知范围不能为空");
        }
        return NotificationScopeVO.from(scope);
    }

    private NotificationResponseDTO toNotificationResponseDTO(NotificationEntity notificationEntity) {
        NotificationResponseDTO dto = new NotificationResponseDTO();
        dto.setId(notificationEntity.getNotificationId());
        dto.setSeq(notificationEntity.getSeq());
        dto.setSenderId(notificationEntity.getSenderId());
        dto.setType(notificationEntity.getType().name());
        dto.setTitle(notificationEntity.getTitle());
        dto.setContent(notificationEntity.getContent());
        dto.setRead(notificationEntity.isRead());
        dto.setRefType(notificationEntity.getRefType());
        dto.setRefId(notificationEntity.getRefId());
        dto.setCreatedAt(notificationEntity.getCreatedAt());
        return dto;
    }
}
