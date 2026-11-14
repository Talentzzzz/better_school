package com.betterschool.trigger.http;

import com.betterschool.api.ISearchService;
import com.betterschool.api.dto.ErrandOrderResponseDTO;
import com.betterschool.api.dto.PostSummaryResponseDTO;
import com.betterschool.api.dto.ProductResponseDTO;
import com.betterschool.api.dto.UserSearchResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.search.model.valobj.SearchPageVO;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 站内搜索 HTTP 入口：免登录浏览。
 * ES 只负责命中 + 排序，展示字段由搜索服务按 id 从 MySQL 反查组装（相关度顺序保持）
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController implements ISearchService {

    private static final int MAX_PAGE_SIZE = 50;

    /** 域名服务与 api 契约同名，字段用全限定名区分 */
    private final com.betterschool.domain.search.service.ISearchService searchService;
    private final com.betterschool.domain.community.service.post.ICommunityPostService postService;

    @Override
    @GetMapping("/posts")
    public Response<PageResult<PostSummaryResponseDTO>> searchPosts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireKeyword(keyword);
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        SearchPageVO<PostEntity> result = searchService.searchPosts(keyword.trim(), page, pageSize);
        String currentUserId = SecurityUtils.currentUserId();
        Set<String> likedIds = new HashSet<>(postService.listLikedPostIds(currentUserId,
                result.getItems().stream().map(PostEntity::getPostId).toList()));
        List<PostSummaryResponseDTO> dtoList = result.getItems().stream()
                .map(post -> toPostSummaryDTO(post, likedIds.contains(post.getPostId()))).toList();
        return Response.ok(new PageResult<>(page, pageSize, result.getTotal(), dtoList));
    }

    @Override
    @GetMapping("/products")
    public Response<PageResult<ProductResponseDTO>> searchProducts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireKeyword(keyword);
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        SearchPageVO<ProductEntity> result = searchService.searchProducts(keyword.trim(), page, pageSize);
        List<ProductResponseDTO> dtoList = result.getItems().stream()
                .map(product -> toProductDTO(product)).toList();
        return Response.ok(new PageResult<>(page, pageSize, result.getTotal(), dtoList));
    }

    @Override
    @GetMapping("/errands")
    public Response<PageResult<ErrandOrderResponseDTO>> searchErrands(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireKeyword(keyword);
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        SearchPageVO<ErrandOrderEntity> result = searchService.searchErrands(keyword.trim(), page, pageSize);
        List<ErrandOrderResponseDTO> dtoList = result.getItems().stream()
                .map(this::toErrandDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, result.getTotal(), dtoList));
    }

    @Override
    @GetMapping("/users")
    public Response<PageResult<UserSearchResponseDTO>> searchUsers(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireKeyword(keyword);
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        SearchPageVO<UserEntity> result = searchService.searchUsers(keyword.trim(), page, pageSize);
        List<UserSearchResponseDTO> dtoList = result.getItems().stream()
                .map(user -> new UserSearchResponseDTO(user.getUserId(), user.getNickname(), user.getAvatar()))
                .toList();
        return Response.ok(new PageResult<>(page, pageSize, result.getTotal(), dtoList));
    }

    private void requireKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "搜索关键词不能为空");
        }
    }

    /** 访客视角摘要：不暴露浏览量（对齐社区列表）；liked = 当前用户点赞态，content 供客户端过滤 */
    private PostSummaryResponseDTO toPostSummaryDTO(PostEntity postEntity, boolean liked) {
        PostSummaryResponseDTO dto = new PostSummaryResponseDTO();
        dto.setPostId(postEntity.getPostId());
        dto.setTitle(postEntity.getTitle());
        dto.setCover(postEntity.displayCover());
        dto.setUserId(postEntity.getUserId());
        dto.setNickname(postEntity.getAuthorNickname());
        dto.setAvatar(postEntity.getAuthorAvatar());
        dto.setLikeCount(postEntity.getLikeCount());
        dto.setCreatedAt(postEntity.getCreatedAt());
        dto.setLiked(liked);
        dto.setContent(postEntity.getContent());
        return dto;
    }

    private ProductResponseDTO toProductDTO(ProductEntity productEntity) {
        ProductResponseDTO dto = new ProductResponseDTO();
        dto.setProductId(productEntity.getProductId());
        dto.setUserId(productEntity.getUserId());
        dto.setSellerNickname(productEntity.getSellerNickname());
        dto.setSellerAvatar(productEntity.getSellerAvatar());
        dto.setTitle(productEntity.getTitle());
        dto.setDescription(productEntity.getDescription());
        dto.setImages(productEntity.getImages());
        dto.setPrice(productEntity.getPrice());
        dto.setTradeLocation(productEntity.getTradeLocation());
        dto.setCategory(productEntity.getCategory().name());
        dto.setStatus(productEntity.getStatus().name());
        dto.setCreatedAt(productEntity.getCreatedAt());
        return dto;
    }

    private ErrandOrderResponseDTO toErrandDTO(ErrandOrderEntity orderEntity) {
        ErrandOrderResponseDTO dto = new ErrandOrderResponseDTO();
        dto.setOrderId(orderEntity.getOrderId());
        dto.setPublisherId(orderEntity.getPublisherId());
        dto.setPublisherNickname(orderEntity.getPublisherNickname());
        dto.setPublisherAvatar(orderEntity.getPublisherAvatar());
        dto.setTakerId(orderEntity.getTakerId());
        dto.setTakerNickname(orderEntity.getTakerNickname());
        dto.setTakerAvatar(orderEntity.getTakerAvatar());
        dto.setType(orderEntity.getType().name());
        dto.setTitle(orderEntity.getTitle());
        dto.setDescription(orderEntity.getDescription());
        dto.setPickupLocation(orderEntity.getPickupLocation());
        dto.setDeliveryLocation(orderEntity.getDeliveryLocation());
        dto.setReward(orderEntity.getReward());
        dto.setCurrentTakers(orderEntity.getCurrentTakers());
        dto.setDeadline(orderEntity.getDeadline());
        dto.setStatus(orderEntity.getStatus().name());
        dto.setCreatedAt(orderEntity.getCreatedAt());
        return dto;
    }
}
