package com.betterschool.trigger.http;

import com.betterschool.api.ISearchService;
import com.betterschool.api.dto.PostSummaryResponseDTO;
import com.betterschool.api.dto.ProductResponseDTO;
import com.betterschool.api.dto.UserSearchResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.service.profile.IUserProfileService;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.common.SearchPageVO;
import com.betterschool.types.enums.ResponseCodeEnum;
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
 * 站内搜索 HTTP 入口：免登录浏览，三个 tab 各查各的域（不经 case 编排——没有跨域组合）。
 * 关键词命中在本域仓储的倒排索引，展示字段由领域服务按 id 回库组装（相关度顺序保持）
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController implements ISearchService {

    private static final int MAX_PAGE_SIZE = 50;

    private final IPostService postService;
    private final IIdleService idleService;
    private final IUserProfileService userProfileService;

    @Override
    @GetMapping("/posts")
    public Response<PageResult<PostSummaryResponseDTO>> searchPosts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireKeyword(keyword);
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        SearchPageVO<PostEntity> result = postService.searchPosts(keyword.trim(), page, pageSize);
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
        SearchPageVO<ProductEntity> result = idleService.searchProducts(keyword.trim(), page, pageSize);
        List<ProductResponseDTO> dtoList = result.getItems().stream()
                .map(product -> toProductDTO(product)).toList();
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
        SearchPageVO<UserEntity> result = userProfileService.searchUsers(keyword.trim(), page, pageSize);
        List<UserSearchResponseDTO> dtoList = result.getItems().stream()
                .map(user -> new UserSearchResponseDTO(user.getUserId(), user.getNickname(), user.getAvatar()))
                .toList();
        return Response.ok(new PageResult<>(page, pageSize, result.getTotal(), dtoList));
    }

    private void requireKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "搜索关键词不能为空");
        }
    }

    /** 访客视角摘要：不暴露浏览量（对齐社区列表）；liked = 当前用户点赞态，content 供客户端过滤 */
    private PostSummaryResponseDTO toPostSummaryDTO(PostEntity postEntity, boolean liked) {
        PostSummaryResponseDTO postSummaryResponseDTO = new PostSummaryResponseDTO();
        postSummaryResponseDTO.setPostId(postEntity.getPostId());
        postSummaryResponseDTO.setTitle(postEntity.getTitle());
        postSummaryResponseDTO.setCover(postEntity.displayCover());
        postSummaryResponseDTO.setUserId(postEntity.getUserId());
        postSummaryResponseDTO.setNickname(postEntity.getAuthorNickname());
        postSummaryResponseDTO.setAvatar(postEntity.getAuthorAvatar());
        postSummaryResponseDTO.setLikeCount(postEntity.getLikeCount());
        postSummaryResponseDTO.setCreatedAt(postEntity.getCreatedAt());
        postSummaryResponseDTO.setLiked(liked);
        postSummaryResponseDTO.setContent(postEntity.getContent());
        return postSummaryResponseDTO;
    }

    private ProductResponseDTO toProductDTO(ProductEntity productEntity) {
        ProductResponseDTO productResponseDTO = new ProductResponseDTO();
        productResponseDTO.setProductId(productEntity.getProductId());
        productResponseDTO.setUserId(productEntity.getUserId());
        productResponseDTO.setSellerNickname(productEntity.getSellerNickname());
        productResponseDTO.setSellerAvatar(productEntity.getSellerAvatar());
        productResponseDTO.setTitle(productEntity.getTitle());
        productResponseDTO.setDescription(productEntity.getDescription());
        productResponseDTO.setImages(productEntity.getImages());
        productResponseDTO.setPrice(productEntity.getPrice());
        productResponseDTO.setTradeLocation(productEntity.getTradeLocation());
        productResponseDTO.setCategory(productEntity.getCategory().name());
        productResponseDTO.setStatus(productEntity.getStatus().name());
        productResponseDTO.setCreatedAt(productEntity.getCreatedAt());
        return productResponseDTO;
    }

}
