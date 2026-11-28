package com.betterschool.trigger.http;

import com.betterschool.api.INotificationService;
import com.betterschool.api.dto.NotificationBroadcastRequestDTO;
import com.betterschool.api.dto.NotificationReadRequestDTO;
import com.betterschool.api.dto.NotificationResponseDTO;
import com.betterschool.api.dto.NotificationUnreadCountResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCodeEnum;
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
        NotificationScopeEnum notificationScopeVO = parseScope(scope);
        String userId = SecurityUtils.currentUserId();
        long total = notificationService.countNotifications(userId, notificationScopeVO);
        List<NotificationEntity> notificationList = notificationService.listNotifications(userId, notificationScopeVO, page, pageSize);
        List<NotificationResponseDTO> dtoList = notificationList.stream().map(this::toNotificationResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/notifications/unread-count")
    public Response<NotificationUnreadCountResponseDTO> unreadCount(@RequestParam String scope) {
        long count = notificationService.unreadCount(SecurityUtils.currentUserId(), requireScope(scope));
        NotificationUnreadCountResponseDTO notificationUnreadCountResponseDTO = new NotificationUnreadCountResponseDTO();
        notificationUnreadCountResponseDTO.setCount(count);
        return Response.ok(notificationUnreadCountResponseDTO);
    }

    @Override
    @PostMapping("/notifications/read")
    public Response<Void> markRead(@Valid @RequestBody NotificationReadRequestDTO notificationReadRequestDTO) {
        notificationService.markRead(SecurityUtils.currentUserId(), requireScope(notificationReadRequestDTO.getScope()));
        return Response.ok(null);
    }

    @Override
    @PostMapping("/notifications/broadcast")
    public Response<Void> broadcast(@Valid @RequestBody NotificationBroadcastRequestDTO notificationBroadcastRequestDTO) {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "无权限，仅管理员可发布公告");
        }
        notificationService.broadcast(notificationBroadcastRequestDTO.getType(), notificationBroadcastRequestDTO.getTitle(), notificationBroadcastRequestDTO.getContent());
        return Response.ok(null);
    }

    /** 列表的 scope 可省略（不过滤）；unread-count / read 的 scope 必填，空白也按参数错误拒绝 */
    private NotificationScopeEnum parseScope(String scope) {
        return scope == null || scope.isBlank() ? null : NotificationScopeEnum.from(scope);
    }

    private NotificationScopeEnum requireScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "通知范围不能为空");
        }
        return NotificationScopeEnum.from(scope);
    }

    private NotificationResponseDTO toNotificationResponseDTO(NotificationEntity notificationEntity) {
        NotificationResponseDTO notificationResponseDTO = new NotificationResponseDTO();
        notificationResponseDTO.setId(notificationEntity.getNotificationId());
        notificationResponseDTO.setSeq(notificationEntity.getSeq());
        notificationResponseDTO.setSenderId(notificationEntity.getSenderId());
        notificationResponseDTO.setType(notificationEntity.getType().name());
        notificationResponseDTO.setTitle(notificationEntity.getTitle());
        notificationResponseDTO.setContent(notificationEntity.getContent());
        notificationResponseDTO.setRead(notificationEntity.isRead());
        notificationResponseDTO.setRefType(notificationEntity.getRefType());
        notificationResponseDTO.setRefId(notificationEntity.getRefId());
        notificationResponseDTO.setCreatedAt(notificationEntity.getCreatedAt());
        return notificationResponseDTO;
    }
}
