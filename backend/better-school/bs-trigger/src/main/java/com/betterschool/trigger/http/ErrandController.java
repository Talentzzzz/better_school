package com.betterschool.trigger.http;

import com.betterschool.api.IErrandService;
import com.betterschool.api.dto.ErrandDraftSaveRequestDTO;
import com.betterschool.api.dto.ErrandOrderPublishRequestDTO;
import com.betterschool.api.dto.ErrandOrderResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.usecase.errand.ErrandOrderCase;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandFeedPageVO;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 跑腿 HTTP 入口：GET 浏览免登录，发单/支付/抢单/取货/送达/确认/取消/仲裁需登录；
 * 跨域编排委托 ErrandOrderCase（事务 + 资金 + 信用分）
 */
@RestController
@RequestMapping("/api/errand")
@RequiredArgsConstructor
public class ErrandController implements IErrandService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ErrandOrderCase errandOrderCase;

    @Override
    @PostMapping("/orders")
    public Response<String> createOrder(@Valid @RequestBody ErrandOrderPublishRequestDTO request) {
        String orderId = errandOrderCase.createOrder(SecurityUtils.currentUserId(), request.getType(),
                request.getDescription(), request.getPickupLocation(), request.getDeliveryLocation(),
                request.getReward(), request.getImages(), request.getTimeLimit());
        return Response.ok(orderId);
    }

    @Override
    @PostMapping("/orders/{orderId}/pay")
    public Response<Void> payOrder(@PathVariable String orderId) {
        errandOrderCase.payOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/draft")
    public Response<String> saveDraft(@Valid @RequestBody ErrandDraftSaveRequestDTO request) {
        String orderId = errandOrderCase.createDraft(SecurityUtils.currentUserId(), request.getType(),
                request.getDescription(), request.getPickupLocation(), request.getDeliveryLocation(),
                request.getReward(), request.getImages(), request.getTimeLimit());
        return Response.ok(orderId);
    }

    @Override
    @PutMapping("/orders/{orderId}/draft")
    public Response<Void> updateDraft(@PathVariable String orderId,
                                      @Valid @RequestBody ErrandDraftSaveRequestDTO request) {
        errandOrderCase.updateDraft(orderId, SecurityUtils.currentUserId(), request.getType(),
                request.getDescription(), request.getPickupLocation(), request.getDeliveryLocation(),
                request.getReward(), request.getImages(), request.getTimeLimit());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/submit")
    public Response<Void> submitDraft(@PathVariable String orderId) {
        errandOrderCase.submitDraft(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @DeleteMapping("/orders/{orderId}")
    public Response<Void> deleteDraft(@PathVariable String orderId) {
        errandOrderCase.deleteDraft(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/orders")
    public Response<PageResult<ErrandOrderResponseDTO>> listOrders(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "PUBLISHED") String status,
            @RequestParam(defaultValue = "time") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        // 索引 + 游标分页（票 04）：无类型筛选且只看 PUBLISHED 时走索引序扫描，total 降口径 -1、
        // 续页靠 cursor；带筛选或索引查询降级时 indexPath=false，total 仍精确、按 page 续页
        ErrandFeedPageVO feedPage = errandOrderCase.listOrders(type, status, sort, cursor, page, pageSize);
        long total = feedPage.isIndexPath() ? -1 : errandOrderCase.countOrders(type, status);
        List<ErrandOrderResponseDTO> dtoList = feedPage.getOrders().stream().map(this::toOrderResponseDTO).toList();
        // 已读灰显（market-feed-uv 票 05，照帖子 listPosts）：页内 order_id 批量回查浏览明细，
        // 登录用户带 viewed 标记；未登录为空集 ⇒ 恒 false（大厅匿名可浏览）
        Set<String> viewedIds = viewedOrderIdsOf(feedPage.getOrders(), SecurityUtils.currentUserId());
        dtoList.forEach(dto -> dto.setViewed(viewedIds.contains(dto.getOrderId())));
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList, feedPage.getNextCursor()));
    }

    @Override
    @GetMapping("/orders/{orderId}")
    public Response<ErrandOrderResponseDTO> orderDetail(@PathVariable String orderId) {
        String currentUserId = SecurityUtils.currentUserId();
        ErrandOrderEntity orderEntity = errandOrderCase.orderDetail(orderId, currentUserId);
        return Response.ok(toOrderResponseDTO(orderEntity));
    }

    @Override
    @PostMapping("/orders/{orderId}/grab")
    public Response<Void> grabOrder(@PathVariable String orderId) {
        errandOrderCase.grabOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/accept")
    public Response<Void> acceptLockedOrder(@PathVariable String orderId) {
        errandOrderCase.acceptLockedOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/abandon")
    public Response<Void> abandonLockedOrder(@PathVariable String orderId) {
        errandOrderCase.abandonLockedOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/pickup")
    public Response<Void> pickupOrder(@PathVariable String orderId) {
        errandOrderCase.pickupOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/deliver")
    public Response<Void> deliverOrder(@PathVariable String orderId) {
        errandOrderCase.deliverOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/confirm")
    public Response<Void> confirmOrder(@PathVariable String orderId) {
        errandOrderCase.confirmOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/cancel")
    public Response<Void> cancelOrder(@PathVariable String orderId) {
        errandOrderCase.cancelOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/dispute")
    public Response<Void> disputeOrder(@PathVariable String orderId) {
        errandOrderCase.disputeOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/arbitrate")
    public Response<Void> arbitrateOrder(@PathVariable String orderId, @RequestParam String result) {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权限，仅管理员可仲裁结案");
        }
        errandOrderCase.arbitrateOrder(orderId, SecurityUtils.currentUserId(), result);
        return Response.ok(null);
    }

    @Override
    @GetMapping("/orders/mine")
    public Response<PageResult<ErrandOrderResponseDTO>> listMyOrders(
            @RequestParam String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        long total = errandOrderCase.countMyOrders(role, status, userId);
        List<ErrandOrderEntity> orderList = errandOrderCase.listMyOrders(role, status, userId, page, pageSize);
        List<ErrandOrderResponseDTO> dtoList = orderList.stream().map(this::toOrderResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/users/{userId}/orders")
    public Response<PageResult<ErrandOrderResponseDTO>> listUserOrders(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = errandOrderCase.countUserOrders(userId);
        List<ErrandOrderEntity> orderList = errandOrderCase.listUserOrders(userId, page, pageSize);
        List<ErrandOrderResponseDTO> dtoList = orderList.stream().map(this::toOrderResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    /** 当前用户浏览过的订单 id 集合（未登录为空集；一页一次批量查询，不做 N+1） */
    private Set<String> viewedOrderIdsOf(List<ErrandOrderEntity> orderList, String currentUserId) {
        List<String> orderIds = orderList.stream().map(ErrandOrderEntity::getOrderId).toList();
        return new HashSet<>(errandOrderCase.listViewedOrderIds(currentUserId, orderIds));
    }

    private ErrandOrderResponseDTO toOrderResponseDTO(ErrandOrderEntity orderEntity) {
        ErrandOrderResponseDTO dto = new ErrandOrderResponseDTO();
        dto.setOrderId(orderEntity.getOrderId());
        dto.setPublisherId(orderEntity.getPublisherId());
        dto.setPublisherNickname(orderEntity.getPublisherNickname());
        dto.setPublisherAvatar(orderEntity.getPublisherAvatar());
        dto.setTakerId(orderEntity.getTakerId());
        dto.setTakerNickname(orderEntity.getTakerNickname());
        dto.setTakerAvatar(orderEntity.getTakerAvatar());
        dto.setType(orderEntity.getType() == null ? null : orderEntity.getType().name());
        dto.setDescription(orderEntity.getDescription());
        dto.setPickupLocation(orderEntity.getPickupLocation());
        dto.setDeliveryLocation(orderEntity.getDeliveryLocation());
        dto.setReward(orderEntity.getReward());
        dto.setCurrentTakers(orderEntity.getCurrentTakers());
        // 浏览 UV 数：详情走计数窄表精确值；大厅两条路径都读计数（索引路径取索引热度快照——同一合并事务
        // 推进，数值一致；筛选 / 降级直查取 selectPage 的计数窄表 JOIN），market-feed-uv 票 04
        dto.setViewCount(orderEntity.getViewCount());
        dto.setDeadline(orderEntity.getDeadline());
        dto.setStatus(orderEntity.getStatus().name());
        dto.setCreatedAt(orderEntity.getCreatedAt());
        dto.setImages(orderEntity.getImages());
        return dto;
    }

}
