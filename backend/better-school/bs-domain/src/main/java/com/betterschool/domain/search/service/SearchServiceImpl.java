package com.betterschool.domain.search.service;

import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.search.adapter.port.ISearchQueryPort;
import com.betterschool.domain.search.adapter.repository.ISearchRepository;
import com.betterschool.domain.search.model.valobj.PostSearchDocVO;
import com.betterschool.domain.search.model.valobj.ProductSearchDocVO;
import com.betterschool.domain.search.model.valobj.SearchPageVO;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.search.model.valobj.UserSearchDocVO;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.valobj.ProductStatusVO;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.UserStatusVO;
import com.betterschool.types.concurrent.VirtualThreadFanout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 搜索服务实现：只注入自己的仓储 + 自己的跨域只读 port（不注入任何他域仓储、不注入域 service）。
 * 同步策略：事件只带 {type,id}，消费方反查构建——重复消息无副作用（幂等）；丢失消息由全量重建兜底（自愈）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    /** 全量重建的游标批大小：批 = 一次「拉批 + 批内扇出」，宽度 = 批大小 */
    private static final int REBUILD_BATCH_SIZE = 500;

    private final ISearchRepository searchRepository;
    private final ISearchQueryPort searchQueryPort;
    private final VirtualThreadFanout fanout;

    @Override
    public void handleSync(SearchSyncMessage message) {
        // 消息 id 统一 String：业务 id 均为 UUID 字符串，直传不再解析（uuid-biz-id 票 05）
        switch (message.getType()) {
            case "post" -> syncPost(message.getId());
            case "product" -> syncProduct(message.getId());
            case "user" -> syncUser(message.getId());
            default -> log.warn("[SearchSync] 未知实体类型 type={}, id={}", message.getType(), message.getId());
        }
    }

    private void syncPost(String postId) {
        PostEntity postEntity = searchQueryPort.findPost(postId);
        if (postEntity != null && postEntity.getStatus() == PostStatusVO.NORMAL) {
            searchRepository.bulkUpsertPostDocs(List.of(toPostSearchDocVO(postEntity)));
        } else {
            searchRepository.deletePostDoc(postId);
        }
    }

    private void syncProduct(String productId) {
        ProductEntity productEntity = searchQueryPort.findProduct(productId);
        if (productEntity != null && productEntity.getStatus() == ProductStatusVO.ON_SALE) {
            searchRepository.bulkUpsertProductDocs(List.of(toProductSearchDocVO(productEntity)));
        } else {
            searchRepository.deleteProductDoc(productId);
        }
    }

    private void syncUser(String userId) {
        UserEntity userEntity = searchQueryPort.findUser(userId);
        if (userEntity != null && userEntity.getStatus() == UserStatusVO.NORMAL) {
            searchRepository.bulkUpsertUserDocs(List.of(toUserSearchDocVO(userEntity)));
        } else {
            searchRepository.deleteUserDoc(userId);
        }
    }

    @Override
    public SearchPageVO<PostEntity> searchPosts(String keyword, int page, int pageSize) {
        SearchPageVO<String> idPage = searchRepository.searchPosts(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<String, PostEntity> byId = searchQueryPort.listPostsByIds(idPage.getItems()).stream()
                .filter(post -> post.getStatus() == PostStatusVO.NORMAL)
                .collect(Collectors.toMap(PostEntity::getPostId, Function.identity()));
        return new SearchPageVO<>(idPage.getTotal(), orderByIds(idPage.getItems(), byId));
    }

    @Override
    public SearchPageVO<ProductEntity> searchProducts(String keyword, int page, int pageSize) {
        SearchPageVO<String> idPage = searchRepository.searchProducts(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<String, ProductEntity> byId = searchQueryPort.listProductsByIds(idPage.getItems()).stream()
                .filter(product -> product.getStatus() == ProductStatusVO.ON_SALE)
                .collect(Collectors.toMap(ProductEntity::getProductId, Function.identity()));
        return new SearchPageVO<>(idPage.getTotal(), orderByIds(idPage.getItems(), byId));
    }

    @Override
    public SearchPageVO<UserEntity> searchUsers(String keyword, int page, int pageSize) {
        SearchPageVO<String> idPage = searchRepository.searchUsers(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<String, UserEntity> byId = searchQueryPort.listUsersByIds(idPage.getItems()).stream()
                .filter(user -> user.getStatus() == UserStatusVO.NORMAL)
                .collect(Collectors.toMap(UserEntity::getUserId, Function.identity()));
        return new SearchPageVO<>(idPage.getTotal(), orderByIds(idPage.getItems(), byId));
    }

    /** IN 查询返回顺序 ≠ ES 相关度顺序：按命中 id 顺序重排，命中后状态变化的实体过滤掉 */
    private static <I, T> List<T> orderByIds(List<I> ids, Map<I, T> byId) {
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Override
    public void rebuildAllIndices() {
        rebuildPosts();
        rebuildProducts();
        rebuildUsers();
        log.info("[SearchRebuild] 全量重建完成");
    }

    /**
     * 重建一类索引：游标拉批（宽度 = 批大小）→ 批内扇出反查转文档 → 回主线程过滤汇总成一次 bulk 写。
     * 游标推进到空批是**唯一**终止条件——旧实现「只取前 N 行」等于静默不完整的索引。
     * 游标与 id 同型：游标是 UUID 字符串业务 id，字典序推进（uuid-biz-id 票 05）
     */
    private <I, T> void rebuild(String index, String threadPrefix, I startId,
                                BiFunction<I, Integer, List<I>> pageOfIds,
                                Function<I, T> docOf, Consumer<List<T>> bulkUpsert) {
        searchRepository.clearIndex(index);
        int total = 0;
        I lastId = startId;
        while (true) {
            List<I> ids = pageOfIds.apply(lastId, REBUILD_BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            List<T> docs = fanout.map(threadPrefix, ids, docOf).stream().filter(Objects::nonNull).toList();
            bulkUpsert.accept(docs);
            total += docs.size();
            lastId = ids.get(ids.size() - 1);
        }
        log.info("[SearchRebuild] {} 索引回填 {} 条", index, total);
    }

    private void rebuildPosts() {
        rebuild("post", "search-rebuild-post-", "",
                (cursor, limit) -> searchQueryPort.listPostIdsAfter(cursor, limit),
                this::postSearchDocOf, searchRepository::bulkUpsertPostDocs);
    }

    private void rebuildProducts() {
        rebuild("product", "search-rebuild-product-", "",
                (cursor, limit) -> searchQueryPort.listProductIdsAfter(cursor, limit),
                this::productSearchDocOf, searchRepository::bulkUpsertProductDocs);
    }

    private void rebuildUsers() {
        rebuild("user", "search-rebuild-user-", "",
                (cursor, limit) -> searchQueryPort.listUserIdsAfter(cursor, limit),
                this::userSearchDocOf, searchRepository::bulkUpsertUserDocs);
    }

    /** 扇出项 = 一个 id：反查实体转文档，不进索引的（状态不符/已删）返回 null 由主线程过滤 */

    private PostSearchDocVO postSearchDocOf(String postId) {
        return searchQueryPort.listPostsByIds(List.of(postId)).stream()
                .filter(post -> post.getStatus() == PostStatusVO.NORMAL)
                .map(this::toPostSearchDocVO)
                .findFirst()
                .orElse(null);
    }

    private ProductSearchDocVO productSearchDocOf(String productId) {
        return searchQueryPort.listProductsByIds(List.of(productId)).stream()
                .filter(product -> product.getStatus() == ProductStatusVO.ON_SALE)
                .map(this::toProductSearchDocVO)
                .findFirst()
                .orElse(null);
    }

    private UserSearchDocVO userSearchDocOf(String userId) {
        return searchQueryPort.listUsersByIds(List.of(userId)).stream()
                .filter(user -> user.getStatus() == UserStatusVO.NORMAL)
                .map(this::toUserSearchDocVO)
                .findFirst()
                .orElse(null);
    }

    private PostSearchDocVO toPostSearchDocVO(PostEntity postEntity) {
        PostSearchDocVO doc = new PostSearchDocVO();
        doc.setPostId(postEntity.getPostId());
        doc.setTitle(postEntity.getTitle());
        doc.setContent(postEntity.getContent());
        doc.setStatus(postEntity.getStatus().name());
        doc.setCreatedAt(postEntity.getCreatedAt());
        return doc;
    }

    private ProductSearchDocVO toProductSearchDocVO(ProductEntity productEntity) {
        ProductSearchDocVO doc = new ProductSearchDocVO();
        doc.setProductId(productEntity.getProductId());
        doc.setTitle(productEntity.getTitle());
        doc.setDescription(productEntity.getDescription());
        doc.setTradeLocation(productEntity.getTradeLocation());
        doc.setCategory(productEntity.getCategory().name());
        doc.setStatus(productEntity.getStatus().name());
        doc.setCreatedAt(productEntity.getCreatedAt());
        return doc;
    }

    private UserSearchDocVO toUserSearchDocVO(UserEntity userEntity) {
        UserSearchDocVO doc = new UserSearchDocVO();
        doc.setUserId(userEntity.getUserId());
        doc.setNickname(userEntity.getNickname());
        doc.setStatus(userEntity.getStatus().name());
        return doc;
    }
}
