package com.betterschool.integration.support;

import com.betterschool.infrastructure.search.ISearchIndex;
import com.betterschool.infrastructure.search.SearchIndexName;
import com.betterschool.types.common.SearchPageVO;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 倒排索引技术组件的测试替身：测试栈把 ES 指向必然不可达的端口（见 application-test.yml），
 * 索引全量重建这条兜底链路没法连真 ES 跑。替身把「清了哪些索引、写了哪些文档 id」记下来，
 * 让重建的<b>游标拉批 + 批内回查 + 状态过滤 + 批量写入</b>在真 MySQL 上跑一遍。
 *
 * <p>断言的是重建算法，不是 Elasticsearch——真 ES 的读写语义不在测试栈的覆盖范围。
 * 只存在于 test classpath，生产打包不含。
 */
@Component
@Primary
public class FakeSearchIndex implements ISearchIndex {

    private static final List<String> CLEARED_INDEXES = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> POST_IDS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> PRODUCT_IDS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> USER_IDS = Collections.synchronizedSet(new LinkedHashSet<>());

    @Override
    public void ensureIndices() {
        // 索引在 ES 侧，测试栈不连 ES
    }

    @Override
    public void recreate(String indexName) {
        CLEARED_INDEXES.add(shortName(indexName));
        bucketOf(indexName).clear();
    }

    @Override
    public SearchPageVO<String> search(String indexName, List<String> searchFields, List<String> publicStatuses,
                                       String keyword, int page, int pageSize) {
        return new SearchPageVO<>(0, List.of());
    }

    @Override
    public <T> void bulkUpsert(String indexName, List<T> documents, Function<T, String> idOf) {
        Set<String> bucket = bucketOf(indexName);
        documents.forEach(doc -> bucket.add(idOf.apply(doc)));
    }

    @Override
    public void deleteDoc(String indexName, String documentId) {
        bucketOf(indexName).remove(documentId);
    }

    public static void reset() {
        CLEARED_INDEXES.clear();
        POST_IDS.clear();
        PRODUCT_IDS.clear();
        USER_IDS.clear();
    }

    /** 重建清索引的顺序：post → product → user */
    public static List<String> clearedIndexes() {
        return List.copyOf(CLEARED_INDEXES);
    }

    public static Set<String> postIds() {
        return Set.copyOf(POST_IDS);
    }

    public static Set<String> productIds() {
        return Set.copyOf(PRODUCT_IDS);
    }

    public static Set<String> userIds() {
        return Set.copyOf(USER_IDS);
    }

    private static Set<String> bucketOf(String indexName) {
        return switch (indexName) {
            case SearchIndexName.POST -> POST_IDS;
            case SearchIndexName.PRODUCT -> PRODUCT_IDS;
            case SearchIndexName.USER -> USER_IDS;
            default -> throw new IllegalArgumentException("测试替身未覆盖的索引：" + indexName);
        };
    }

    /** 断言口径用短名（post/product/user），与索引名解耦 */
    private static String shortName(String indexName) {
        return switch (indexName) {
            case SearchIndexName.POST -> "post";
            case SearchIndexName.PRODUCT -> "product";
            case SearchIndexName.USER -> "user";
            default -> indexName;
        };
    }
}
