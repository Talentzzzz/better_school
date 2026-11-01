package com.betterschool.integration.support;

import com.betterschool.domain.search.adapter.repository.ISearchRepository;
import com.betterschool.domain.search.model.valobj.ErrandSearchDocVO;
import com.betterschool.domain.search.model.valobj.PostSearchDocVO;
import com.betterschool.domain.search.model.valobj.ProductSearchDocVO;
import com.betterschool.domain.search.model.valobj.SearchPageVO;
import com.betterschool.domain.search.model.valobj.UserSearchDocVO;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索仓储的测试替身（票 15）：测试栈把 ES 指向必然不可达的端口（见 application-test.yml），
 * 索引全量重建这条兜底链路没法连真 ES 跑。替身把「清了哪些索引、bulk 写了哪些文档」记下来，
 * 让重建的**游标拉批 + 批内扇出 + 状态过滤 + 回主线程汇总**在真 MySQL 上跑一遍。
 *
 * <p>断言的是重建算法，不是 Elasticsearch——真 ES 的读写语义不在测试栈的覆盖范围（也未变）。
 * 只存在于 test classpath，生产打包不含。
 */
@Component
@Primary
public class FakeSearchRepository implements ISearchRepository {

    private static final List<String> CLEARED_INDEXES = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> POST_IDS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> PRODUCT_IDS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> ERRAND_IDS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> USER_IDS = Collections.synchronizedSet(new LinkedHashSet<>());

    @Override
    public void ensureIndices() {
        // 索引在 ES 侧，测试栈不连 ES
    }

    @Override
    public void clearIndex(String entityType) {
        CLEARED_INDEXES.add(entityType);
    }

    @Override
    public SearchPageVO<String> searchPosts(String keyword, int page, int pageSize) {
        return emptyPage();
    }

    @Override
    public SearchPageVO<String> searchProducts(String keyword, int page, int pageSize) {
        return emptyPage();
    }

    @Override
    public SearchPageVO<String> searchErrands(String keyword, int page, int pageSize) {
        return emptyPage();
    }

    @Override
    public SearchPageVO<String> searchUsers(String keyword, int page, int pageSize) {
        return emptyPage();
    }

    @Override
    public void bulkUpsertPostDocs(List<PostSearchDocVO> docs) {
        docs.forEach(doc -> POST_IDS.add(doc.getPostId()));
    }

    @Override
    public void bulkUpsertProductDocs(List<ProductSearchDocVO> docs) {
        docs.forEach(doc -> PRODUCT_IDS.add(doc.getProductId()));
    }

    @Override
    public void bulkUpsertErrandDocs(List<ErrandSearchDocVO> docs) {
        docs.forEach(doc -> ERRAND_IDS.add(doc.getOrderId()));
    }

    @Override
    public void bulkUpsertUserDocs(List<UserSearchDocVO> docs) {
        docs.forEach(doc -> USER_IDS.add(doc.getUserId()));
    }

    @Override
    public void deletePostDoc(String postId) {
        POST_IDS.remove(postId);
    }

    @Override
    public void deleteProductDoc(String productId) {
        PRODUCT_IDS.remove(productId);
    }

    @Override
    public void deleteErrandDoc(String orderId) {
        ERRAND_IDS.remove(orderId);
    }

    @Override
    public void deleteUserDoc(String userId) {
        USER_IDS.remove(userId);
    }

    public static void reset() {
        CLEARED_INDEXES.clear();
        POST_IDS.clear();
        PRODUCT_IDS.clear();
        ERRAND_IDS.clear();
        USER_IDS.clear();
    }

    /** 重建清索引的顺序：post → product → errand → user */
    public static List<String> clearedIndexes() {
        return List.copyOf(CLEARED_INDEXES);
    }

    public static Set<String> postIds() {
        return Set.copyOf(POST_IDS);
    }

    public static Set<String> productIds() {
        return Set.copyOf(PRODUCT_IDS);
    }

    public static Set<String> errandIds() {
        return Set.copyOf(ERRAND_IDS);
    }

    public static Set<String> userIds() {
        return Set.copyOf(USER_IDS);
    }

    private static <I> SearchPageVO<I> emptyPage() {
        return new SearchPageVO<>(0, List.of());
    }
}
