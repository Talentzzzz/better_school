package com.betterschool.domain.search.service;

import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.search.adapter.port.ISearchQueryPort;
import com.betterschool.domain.search.adapter.repository.ISearchRepository;
import com.betterschool.domain.search.model.valobj.ErrandSearchDocVO;
import com.betterschool.domain.search.model.valobj.PostSearchDocVO;
import com.betterschool.domain.search.model.valobj.ProductSearchDocVO;
import com.betterschool.domain.search.model.valobj.SearchPageVO;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.search.model.valobj.UserSearchDocVO;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.UserStatusVO;
import com.betterschool.types.concurrent.VirtualThreadFanout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 公开可搜索的订单状态：仅"进行中未被接单方/发布方锁定"的公开单（锁单中 LOCKED 从不进搜索索引） */
    private static final Set<ErrandStatusVO> PUBLIC_ERRAND_STATUSES =
            EnumSet.of(ErrandStatusVO.PUBLISHED, ErrandStatusVO.CONFIRMED,
                    ErrandStatusVO.PICKED_UP, ErrandStatusVO.DELIVERED);

    private final ISearchRepository searchRepository;
    private final ISearchQueryPort searchQueryPort;
    private final VirtualThreadFanout fanout;

    @Override
    public void handleSync(SearchSyncMessage message) {
        // 消息 id 统一 String（user_id / post_id 是 UUID）；product/errand 的业务 id 过渡期仍是数值，消费侧解析回 Long
        switch (message.getType()) {
            case "post" -> syncPost(message.getId());
            case "product" -> syncProduct(Long.parseLong(message.getId()));
            case "errand" -> syncErrand(Long.parseLong(message.getId()));
            case "user" -> syncUser(message.getId());
            default -> log.warn("[SearchSync] 未知实体类型 type={}, id={}", message.getType(), message.getId());
        }
    }

    private void syncPost(String postId) {
        PostEntity post = searchQueryPort.findPost(postId);
        if (post != null && post.getStatus() == PostStatusVO.NORMAL) {
            searchRepository.bulkUpsertPostDocs(List.of(toPostSearchDocVO(post)));
        } else {
            searchRepository.deletePostDoc(postId);
        }
    }

    private void syncProduct(Long productId) {
        ProductEntity product = searchQueryPort.findProduct(productId);
        if (product != null && product.getStatus() == ProductStatusVO.ON_SALE) {
            searchRepository.bulkUpsertProductDocs(List.of(toProductSearchDocVO(product)));
        } else {
            searchRepository.deleteProductDoc(productId);
        }
    }

    private void syncErrand(Long orderId) {
        ErrandOrderEntity order = searchQueryPort.findErrand(orderId);
        if (order != null && PUBLIC_ERRAND_STATUSES.contains(order.getStatus())) {
            searchRepository.bulkUpsertErrandDocs(List.of(toErrandSearchDocVO(order)));
        } else {
            searchRepository.deleteErrandDoc(orderId);
        }
    }

    private void syncUser(String userId) {
        UserEntity user = searchQueryPort.findUser(userId);
        if (user != null && user.getStatus() == UserStatusVO.NORMAL) {
            searchRepository.bulkUpsertUserDocs(List.of(toUserSearchDocVO(user)));
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
        SearchPageVO<Long> idPage = searchRepository.searchProducts(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<Long, ProductEntity> byId = searchQueryPort.listProductsByIds(idPage.getItems()).stream()
                .filter(product -> product.getStatus() == ProductStatusVO.ON_SALE)
                .collect(Collectors.toMap(ProductEntity::getProductId, Function.identity()));
        return new SearchPageVO<>(idPage.getTotal(), orderByIds(idPage.getItems(), byId));
    }

    @Override
    public SearchPageVO<ErrandOrderEntity> searchErrands(String keyword, int page, int pageSize) {
        SearchPageVO<Long> idPage = searchRepository.searchErrands(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<Long, ErrandOrderEntity> byId = searchQueryPort.listErrandsByIds(idPage.getItems()).stream()
                .filter(order -> PUBLIC_ERRAND_STATUSES.contains(order.getStatus()))
                .collect(Collectors.toMap(ErrandOrderEntity::getOrderId, Function.identity()));
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
        rebuildErrands();
        rebuildUsers();
        log.info("[SearchRebuild] 全量重建完成");
    }

    /**
     * 重建一类索引：游标拉批（宽度 = 批大小）→ 批内扇出反查转文档 → 回主线程过滤汇总成一次 bulk 写。
     * 游标推进到空批是**唯一**终止条件——旧实现「只取前 N 行」等于静默不完整的索引。
     * 游标与 id 同型（post/user 的游标是 UUID 字符串业务 id，字典序推进；product/errand 过渡期仍是数值 id）
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
        rebuild("product", "search-rebuild-product-", 0L,
                (cursor, limit) -> searchQueryPort.listProductIdsAfter(cursor, limit),
                this::productSearchDocOf, searchRepository::bulkUpsertProductDocs);
    }

    private void rebuildErrands() {
        rebuild("errand", "search-rebuild-errand-", 0L,
                (cursor, limit) -> searchQueryPort.listErrandIdsAfter(cursor, limit),
                this::errandSearchDocOf, searchRepository::bulkUpsertErrandDocs);
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

    private ProductSearchDocVO productSearchDocOf(Long productId) {
        return searchQueryPort.listProductsByIds(List.of(productId)).stream()
                .filter(product -> product.getStatus() == ProductStatusVO.ON_SALE)
                .map(this::toProductSearchDocVO)
                .findFirst()
                .orElse(null);
    }

    private ErrandSearchDocVO errandSearchDocOf(Long orderId) {
        return searchQueryPort.listErrandsByIds(List.of(orderId)).stream()
                .filter(order -> PUBLIC_ERRAND_STATUSES.contains(order.getStatus()))
                .map(this::toErrandSearchDocVO)
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

    private PostSearchDocVO toPostSearchDocVO(PostEntity post) {
        PostSearchDocVO doc = new PostSearchDocVO();
        doc.setPostId(post.getPostId());
        doc.setTitle(post.getTitle());
        doc.setContent(post.getContent());
        doc.setStatus(post.getStatus().name());
        doc.setCreatedAt(post.getCreatedAt());
        return doc;
    }

    private ProductSearchDocVO toProductSearchDocVO(ProductEntity product) {
        ProductSearchDocVO doc = new ProductSearchDocVO();
        doc.setProductId(product.getProductId());
        doc.setTitle(product.getTitle());
        doc.setDescription(product.getDescription());
        doc.setTradeLocation(product.getTradeLocation());
        doc.setCategory(product.getCategory().name());
        doc.setStatus(product.getStatus().name());
        doc.setCreatedAt(product.getCreatedAt());
        return doc;
    }

    private ErrandSearchDocVO toErrandSearchDocVO(ErrandOrderEntity order) {
        ErrandSearchDocVO doc = new ErrandSearchDocVO();
        doc.setOrderId(order.getOrderId());
        doc.setDescription(order.getDescription());
        doc.setPickupLocation(order.getPickupLocation());
        doc.setDeliveryLocation(order.getDeliveryLocation());
        doc.setType(order.getType().name());
        doc.setStatus(order.getStatus().name());
        doc.setCreatedAt(order.getCreatedAt());
        return doc;
    }

    private UserSearchDocVO toUserSearchDocVO(UserEntity user) {
        UserSearchDocVO doc = new UserSearchDocVO();
        doc.setUserId(user.getUserId());
        doc.setNickname(user.getNickname());
        doc.setStatus(user.getStatus().name());
        return doc;
    }
}
