package com.betterschool.infrastructure.adapter.repository.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.search.adapter.repository.ISearchRepository;
import com.betterschool.domain.search.model.valobj.ErrandSearchDocVO;
import com.betterschool.domain.search.model.valobj.PostSearchDocVO;
import com.betterschool.domain.search.model.valobj.ProductSearchDocVO;
import com.betterschool.domain.search.model.valobj.SearchPageVO;
import com.betterschool.domain.search.model.valobj.UserSearchDocVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import com.betterschool.domain.user.model.valobj.UserStatusVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索仓储：Elasticsearch 实现。索引只存 id + 检索/过滤字段（ik_max_word 索引分词 / ik_smart 搜索分词），
 * 展示字段由调用方按 id 从 MySQL 反查组装，避免双写漂移。同步幂等：消费方反查不满足公开条件即删文档。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SearchRepository implements ISearchRepository {

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    /** 状态过滤：与展示层"公开可见"判定一致（冗余兜底，最终以 domain 反查为准） */
    private static final List<String> PUBLIC_POST_STATUSES =
            List.of(PostStatusVO.NORMAL.name());
    private static final List<String> PUBLIC_PRODUCT_STATUSES =
            List.of(ProductStatusVO.ON_SALE.name());
    private static final List<String> PUBLIC_ERRAND_STATUSES =
            List.of(ErrandStatusVO.PUBLISHED.name(), ErrandStatusVO.CONFIRMED.name(),
                    ErrandStatusVO.PICKED_UP.name(), ErrandStatusVO.DELIVERED.name());
    private static final List<String> PUBLIC_USER_STATUSES =
            List.of(UserStatusVO.NORMAL.name());

    @Override
    public void ensureIndices() {
        try {
            createIndexIfAbsent("bs_post", mappings -> {
                mappings.properties("postId", p -> p.keyword(k -> k));
                mappings.properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("content", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("status", p -> p.keyword(k -> k));
                mappings.properties("createdAt", p -> p.date(d -> d));
            });
            createIndexIfAbsent("bs_product", mappings -> {
                mappings.properties("productId", p -> p.keyword(k -> k));
                mappings.properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("description", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("tradeLocation", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("category", p -> p.keyword(k -> k));
                mappings.properties("status", p -> p.keyword(k -> k));
                mappings.properties("createdAt", p -> p.date(d -> d));
            });
            createIndexIfAbsent("bs_errand", mappings -> {
                mappings.properties("orderId", p -> p.keyword(k -> k));
                mappings.properties("description", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("pickupLocation", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("deliveryLocation", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("type", p -> p.keyword(k -> k));
                mappings.properties("status", p -> p.keyword(k -> k));
                mappings.properties("createdAt", p -> p.date(d -> d));
            });
            createIndexIfAbsent("bs_user", mappings -> {
                mappings.properties("userId", p -> p.keyword(k -> k));
                mappings.properties("nickname", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("status", p -> p.keyword(k -> k));
            });
        } catch (IOException e) {
            throw new IllegalStateException("ES 索引初始化失败", e);
        }
    }

    private void createIndexIfAbsent(String indexName, Consumer<TypeMapping.Builder> mappingConfig) throws IOException {
        boolean exists = client.indices().exists(request -> request.index(indexName)).value();
        if (exists) {
            return;
        }
        client.indices().create(CreateIndexRequest.of(request -> request
                .index(indexName)
                .mappings(mapping -> {
                    mappingConfig.accept(mapping);
                    return mapping;
                })));
        log.info("[SearchRepository] 创建索引 {}", indexName);
    }

    @Override
    public void clearIndex(String entityType) {
        String indexName = indexOf(entityType);
        try {
            // 删除再重建：重建期间旧数据不可见，避免新旧混合
            if (client.indices().exists(request -> request.index(indexName)).value()) {
                client.indices().delete(request -> request.index(indexName));
            }
            ensureIndices();
        } catch (IOException e) {
            throw new IllegalStateException("ES 清空索引失败 index=" + indexName, e);
        }
    }

    @Override
    public SearchPageVO<String> searchPosts(String keyword, int page, int pageSize) {
        // post 文档 _id 即 UUID 字符串 post_id，原样返回（与 user 同口径）
        return search("bs_post", List.of("title", "content"), PUBLIC_POST_STATUSES, keyword, page, pageSize,
                Function.identity());
    }

    @Override
    public SearchPageVO<Long> searchProducts(String keyword, int page, int pageSize) {
        return search("bs_product", List.of("title", "description", "tradeLocation"),
                PUBLIC_PRODUCT_STATUSES, keyword, page, pageSize, Long::valueOf);
    }

    @Override
    public SearchPageVO<Long> searchErrands(String keyword, int page, int pageSize) {
        return search("bs_errand", List.of("description", "pickupLocation", "deliveryLocation"),
                PUBLIC_ERRAND_STATUSES, keyword, page, pageSize, Long::valueOf);
    }

    @Override
    public SearchPageVO<String> searchUsers(String keyword, int page, int pageSize) {
        // user 文档 _id 即 UUID 字符串 user_id，原样返回
        return search("bs_user", List.of("nickname"), PUBLIC_USER_STATUSES, keyword, page, pageSize,
                Function.identity());
    }

    private <I> SearchPageVO<I> search(String indexName, List<String> searchFields, List<String> publicStatuses,
                                       String keyword, int page, int pageSize, Function<String, I> idOf) {
        if (keyword == null || keyword.isBlank()) {
            return new SearchPageVO<>(0, List.of());
        }
        try {
            SearchResponse<JsonData> response = client.search(buildSearchRequest(
                    indexName, searchFields, publicStatuses, keyword, page, pageSize), JsonData.class);
            List<I> ids = response.hits().hits().stream()
                    .map(hit -> idOf.apply(hit.id()))
                    .toList();
            long total = response.hits().total() == null ? 0 : response.hits().total().value();
            return new SearchPageVO<>(total, ids);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 索引尚未初始化：按空结果处理（Initializer 兜底创建）
                return new SearchPageVO<>(0, List.of());
            }
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("ES 搜索失败 index=" + indexName, e);
        }
    }

    /** 布尔查询分步构建，避免 lambda 深嵌套（multi_match 关键词 + status 公开集过滤） */
    private SearchRequest buildSearchRequest(String indexName, List<String> searchFields, List<String> publicStatuses,
                                             String keyword, int page, int pageSize) {
        List<FieldValue> statusValues = publicStatuses.stream().map(FieldValue::of).toList();
        TermsQueryField statusField = TermsQueryField.of(terms -> terms.value(statusValues));
        TermsQuery statusQuery = new TermsQuery.Builder()
                .field("status")
                .terms(statusField)
                .build();
        MultiMatchQuery multiMatchQuery = new MultiMatchQuery.Builder()
                .query(keyword)
                .fields(searchFields)
                .build();
        Query query = new Query.Builder()
                .bool(boolQuery -> boolQuery
                        .must(must -> must.multiMatch(multiMatchQuery))
                        .filter(filter -> filter.terms(statusQuery)))
                .build();
        return new SearchRequest.Builder()
                .index(indexName)
                .from(Math.max(0, (page - 1) * pageSize))
                .size(pageSize)
                .query(query)
                .source(source -> source.fetch(false))
                .build();
    }

    @Override
    public void bulkUpsertPostDocs(List<PostSearchDocVO> docs) {
        bulkUpsert("bs_post", docs, PostSearchDocVO::getPostId);
    }

    @Override
    public void bulkUpsertProductDocs(List<ProductSearchDocVO> docs) {
        bulkUpsert("bs_product", docs, ProductSearchDocVO::getProductId);
    }

    @Override
    public void bulkUpsertErrandDocs(List<ErrandSearchDocVO> docs) {
        bulkUpsert("bs_errand", docs, ErrandSearchDocVO::getOrderId);
    }

    @Override
    public void bulkUpsertUserDocs(List<UserSearchDocVO> docs) {
        bulkUpsert("bs_user", docs, UserSearchDocVO::getUserId);
    }

    /**
     * 文档序列化用 Spring ObjectMapper（JavaTimeModule，createdAt 输出 ISO 字符串，ES date 默认格式可解析），
     * 经 JsonData 走原始 JSON 通道，避免 client 内部 mapper 序列化 POJO 的日期格式差异
     */
    // 文档 _id 类型随实体（post/product/errand 数值 id；user 是 UUID 字符串）
    private <T, I> void bulkUpsert(String indexName, List<T> docs, java.util.function.Function<T, I> idGetter) {
        if (docs.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder bulkRequest = new BulkRequest.Builder();
            for (T doc : docs) {
                ObjectNode jsonNode = objectMapper.valueToTree(doc);
                bulkRequest.operations(operation -> operation.index(indexOperation -> indexOperation
                        .index(indexName)
                        .id(String.valueOf(idGetter.apply(doc)))
                        .document(JsonData.of(jsonNode))));
            }
            BulkResponse response = client.bulk(bulkRequest.build());
            if (response.errors()) {
                List<String> failedItems = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.id() + ":" + item.error().reason())
                        .collect(Collectors.toList());
                throw new IllegalStateException("ES bulk 部分失败 index=" + indexName + "，failed=" + failedItems);
            }
        } catch (IOException e) {
            throw new IllegalStateException("ES bulk 失败 index=" + indexName, e);
        }
    }

    @Override
    public void deletePostDoc(String postId) {
        deleteDoc("bs_post", postId);
    }

    @Override
    public void deleteProductDoc(Long productId) {
        deleteDoc("bs_product", productId);
    }

    @Override
    public void deleteErrandDoc(Long orderId) {
        deleteDoc("bs_errand", orderId);
    }

    @Override
    public void deleteUserDoc(String userId) {
        deleteDoc("bs_user", userId);
    }

    private void deleteDoc(String indexName, Object id) {
        try {
            client.delete(request -> request.index(indexName).id(String.valueOf(id)).refresh(Refresh.True));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw e;
            }
            // 404：文档不存在，幂等忽略
        } catch (IOException e) {
            throw new IllegalStateException("ES 删除文档失败 index=" + indexName + ", id=" + id, e);
        }
    }

    private String indexOf(String entityType) {
        return switch (entityType) {
            case "post" -> "bs_post";
            case "product" -> "bs_product";
            case "errand" -> "bs_errand";
            case "user" -> "bs_user";
            default -> throw new IllegalArgumentException("未知实体类型：" + entityType);
        };
    }
}
