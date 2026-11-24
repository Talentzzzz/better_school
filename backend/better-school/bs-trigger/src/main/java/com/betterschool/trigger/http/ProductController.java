package com.betterschool.trigger.http;

import com.betterschool.api.IIdleService;
import com.betterschool.api.dto.ProductDraftSaveRequestDTO;
import com.betterschool.api.dto.ProductEditRequestDTO;
import com.betterschool.api.dto.ProductOrderResponseDTO;
import com.betterschool.api.dto.ProductPublishRequestDTO;
import com.betterschool.api.dto.ProductResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.usecase.idle.IdlePublishCase;
import com.betterschool.usecase.idle.ProductOrderCase;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductFeedPageVO;
import com.betterschool.domain.idle.model.valobj.ProductStatusVO;
import com.betterschool.trigger.http.security.SecurityUtils;
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

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 二手交易 HTTP 入口：浏览免登录，发布/收藏需登录
 */
@RestController
@RequestMapping("/api/idle")
@RequiredArgsConstructor
public class ProductController implements IIdleService {

    private static final int MAX_PAGE_SIZE = 50;

    /** 域名服务与 api 契约同名，字段用全限定名区分（照 SearchController 先例） */
    private final com.betterschool.domain.idle.service.IIdleService idleService;
    private final IdlePublishCase idlePublishCase;
    private final ProductOrderCase productOrderCase;

    @Override
    @GetMapping("/products")
    public Response<PageResult<ProductResponseDTO>> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "time") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        // 索引 + 游标分页（票 04）：无筛选走索引序扫描，total 降口径 -1 不再精确 COUNT、续页靠 cursor；
        // 带筛选或索引查询降级时 indexPath=false，total 仍精确、按 page 续页
        ProductFeedPageVO feedPage = idleService.listProducts(category, minPrice, maxPrice, sort, cursor,
                page, pageSize);
        long total = feedPage.isIndexPath() ? -1 : idleService.countProducts(category, minPrice, maxPrice);
        List<ProductResponseDTO> dtoList = feedPage.getProducts().stream()
                .map(product -> toProductResponseDTO(product, false)).toList();
        // 已读灰显（market-feed-uv 票 05，照帖子 listPosts）：页内 product_id 批量回查浏览明细，
        // 登录用户带 viewed 标记；未登录为空集 ⇒ 恒 false（市集列表匿名可浏览）
        Set<String> viewedIds = viewedProductIdsOf(feedPage.getProducts(), SecurityUtils.currentUserId());
        dtoList.forEach(dto -> dto.setViewed(viewedIds.contains(dto.getProductId())));
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList, feedPage.getNextCursor()));
    }

    @Override
    @PostMapping("/products")
    public Response<String> publishProduct(@Valid @RequestBody ProductPublishRequestDTO request) {
        String productId = idlePublishCase.publishProduct(SecurityUtils.currentUserId(), request.getTitle(),
                request.getDescription(), request.getImages(), request.getPrice(),
                request.getTradeLocation(), request.getCategory(), request.getQuantity());
        return Response.ok(productId);
    }

    @Override
    @PostMapping("/products/draft")
    public Response<String> saveProductDraft(@Valid @RequestBody ProductDraftSaveRequestDTO request) {
        String productId = idlePublishCase.saveProductDraft(SecurityUtils.currentUserId(), request.getTitle(),
                request.getDescription(), request.getImages(), request.getPrice(),
                request.getTradeLocation(), request.getCategory(), request.getQuantity());
        return Response.ok(productId);
    }

    @Override
    @PutMapping("/products/{productId}/draft")
    public Response<Void> updateProductDraft(@PathVariable String productId,
                                             @Valid @RequestBody ProductDraftSaveRequestDTO request) {
        idlePublishCase.updateProductDraft(SecurityUtils.currentUserId(), productId, request.getTitle(),
                request.getDescription(), request.getImages(), request.getPrice(),
                request.getTradeLocation(), request.getCategory(), request.getQuantity());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/products/{productId}/submit")
    public Response<Void> submitProductDraft(@PathVariable String productId) {
        idlePublishCase.submitProductDraft(SecurityUtils.currentUserId(), productId);
        return Response.ok(null);
    }

    @Override
    @PostMapping("/products/{productId}/takedown")
    public Response<Void> takeDownProduct(@PathVariable String productId) {
        idlePublishCase.takeDownProduct(SecurityUtils.currentUserId(), productId);
        return Response.ok(null);
    }

    @Override
    @PutMapping("/products/{productId}")
    public Response<Void> editProduct(@PathVariable String productId,
                                      @Valid @RequestBody ProductEditRequestDTO request) {
        idlePublishCase.editOnSaleProduct(SecurityUtils.currentUserId(), productId, request.getTitle(),
                request.getDescription(), request.getImages(), request.getPrice());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/products/{productId}")
    public Response<ProductResponseDTO> productDetail(@PathVariable String productId) {
        String currentUserId = SecurityUtils.currentUserId();
        ProductEntity productEntity = idleService.productDetail(productId, currentUserId);
        return Response.ok(toProductResponseDTO(productEntity, productEntity.isFavorited()));
    }

    @Override
    @DeleteMapping("/products/{productId}")
    public Response<Void> deleteProduct(@PathVariable String productId) {
        idleService.deleteProduct(productId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/products/{productId}/sold")
    public Response<Void> markSold(@PathVariable String productId) {
        idleService.markSold(productId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/products/{productId}/relist")
    public Response<Void> relistProduct(@PathVariable String productId) {
        idleService.relistProduct(productId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/products/{productId}/stock")
    public Response<Void> restockProduct(@PathVariable String productId, @RequestParam Integer quantity) {
        idlePublishCase.restockProduct(SecurityUtils.currentUserId(), productId, quantity);
        return Response.ok(null);
    }

    @Override
    @PostMapping("/products/{productId}/favorite")
    public Response<Void> favoriteProduct(@PathVariable String productId) {
        idleService.favoriteProduct(productId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @DeleteMapping("/products/{productId}/favorite")
    public Response<Void> unfavoriteProduct(@PathVariable String productId) {
        idleService.unfavoriteProduct(productId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/products/mine")
    public Response<PageResult<ProductResponseDTO>> listMyProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        long total = idleService.countMyProducts(userId);
        List<ProductEntity> productList = idleService.listMyProducts(userId, page, pageSize);
        List<ProductResponseDTO> dtoList = productList.stream()
                .map(product -> toProductResponseDTO(product, false))
                .toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/users/{userId}/products")
    public Response<PageResult<ProductResponseDTO>> listUserProducts(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = idleService.countUserOnSaleProducts(userId);
        List<ProductEntity> productList = idleService.listUserOnSaleProducts(userId, page, pageSize);
        List<ProductResponseDTO> dtoList = productList.stream()
                .map(product -> toProductResponseDTO(product, false)).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/favorites")
    public Response<PageResult<ProductResponseDTO>> listFavorites(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        List<ProductEntity> productList = idleService.listFavorites(SecurityUtils.currentUserId(), page, pageSize);
        List<ProductResponseDTO> dtoList = productList.stream().map(product -> toProductResponseDTO(product, true)).toList();
        return Response.ok(new PageResult<>(page, pageSize, 0, dtoList));
    }

    @Override
    @PostMapping("/orders")
    public Response<String> createProductOrder(@RequestParam String productId,
                                               @RequestParam(defaultValue = "1") Integer quantity) {
        return Response.ok(productOrderCase.createOrder(SecurityUtils.currentUserId(), productId, quantity));
    }

    @Override
    @PostMapping("/orders/{orderId}/pay")
    public Response<Void> payProductOrder(@PathVariable String orderId) {
        productOrderCase.payOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/mark-delivered")
    public Response<Void> markDeliveredProduct(@PathVariable String orderId) {
        productOrderCase.markDelivered(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/confirm")
    public Response<Void> confirmProductOrder(@PathVariable String orderId) {
        productOrderCase.confirmReceived(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/orders/{orderId}/cancel")
    public Response<Void> cancelProductOrder(@PathVariable String orderId) {
        productOrderCase.cancelOrder(orderId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/orders/{orderId}")
    public Response<ProductOrderResponseDTO> productOrderDetail(@PathVariable String orderId) {
        ProductOrderEntity orderEntity = productOrderCase.orderDetail(orderId, SecurityUtils.currentUserId());
        return Response.ok(toProductOrderResponseDTO(orderEntity));
    }

    @Override
    @GetMapping("/orders/mine")
    public Response<PageResult<ProductOrderResponseDTO>> listMyProductOrders(
            @RequestParam(defaultValue = "buy") String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        long total = productOrderCase.countMineOrders(role, userId);
        List<ProductOrderEntity> orderList = productOrderCase.listMineOrders(role, userId, page, pageSize);
        List<ProductOrderResponseDTO> dtoList = orderList.stream().map(this::toProductOrderResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    /** 当前用户浏览过的商品 id 集合（未登录为空集；一页一次批量查询，不做 N+1） */
    private Set<String> viewedProductIdsOf(List<ProductEntity> productList, String currentUserId) {
        List<String> productIds = productList.stream().map(ProductEntity::getProductId).toList();
        return new HashSet<>(idleService.listViewedProductIds(currentUserId, productIds));
    }

    private ProductResponseDTO toProductResponseDTO(ProductEntity productEntity, boolean favorited) {
        ProductResponseDTO dto = new ProductResponseDTO();
        dto.setProductId(productEntity.getProductId());
        dto.setUserId(productEntity.getUserId());
        dto.setSellerNickname(productEntity.getSellerNickname());
        dto.setSellerAvatar(productEntity.getSellerAvatar());
        dto.setTitle(productEntity.getTitle());
        dto.setDescription(productEntity.getDescription());
        dto.setImages(productEntity.getImages());
        dto.setPrice(productEntity.getPrice());
        // 草稿行可空：未填的字段回显 null
        dto.setTradeLocation(productEntity.getTradeLocation());
        dto.setCategory(productEntity.getCategory() == null ? null : productEntity.getCategory().name());
        dto.setStatus(productEntity.getStatus().name());
        dto.setViewCount(productEntity.getViewCount());
        dto.setRemainingQuantity(productEntity.getRemainingQuantity());
        dto.setFavoriteCount(productEntity.getFavoriteCount());
        dto.setFavorited(favorited);
        dto.setCreatedAt(productEntity.getCreatedAt());
        return dto;
    }

    private ProductOrderResponseDTO toProductOrderResponseDTO(ProductOrderEntity orderEntity) {
        ProductOrderResponseDTO dto = new ProductOrderResponseDTO();
        dto.setOrderId(orderEntity.getOrderId());
        dto.setProductId(orderEntity.getProductId());
        dto.setProductTitle(orderEntity.getProductTitle());
        dto.setBuyerId(orderEntity.getBuyerId());
        dto.setBuyerNickname(orderEntity.getBuyerNickname());
        dto.setBuyerAvatar(orderEntity.getBuyerAvatar());
        dto.setSellerId(orderEntity.getSellerId());
        dto.setSellerNickname(orderEntity.getSellerNickname());
        dto.setSellerAvatar(orderEntity.getSellerAvatar());
        dto.setPrice(orderEntity.getPrice());
        dto.setQuantity(orderEntity.getQuantity());
        dto.setStatus(orderEntity.getStatus().name());
        dto.setCreatedAt(orderEntity.getCreatedAt());
        return dto;
    }
}
