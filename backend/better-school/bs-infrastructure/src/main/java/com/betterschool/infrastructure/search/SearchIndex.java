package com.betterschool.infrastructure.search;

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
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
import com.betterschool.types.common.SearchPageVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 倒排索引技术组件（Elasticsearch）：索引只存 id + 检索/过滤字段，
 * 展示字段由调用方按 id 回业务库反查组装，避免双写漂移。
 *
 * <p>本类不认识任何业务实体与状态枚举——索引名/字段来自 {@link SearchIndexName}，
 * 状态白名单与文档对象由各域仓储传入（依赖方向：仓储实现 → 技术组件）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndex implements ISearchIndex {

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    @Override
    public void ensureIndices() {
        try {
            createIndexIfAbsent(SearchIndexName.POST, mappings -> {
                mappings.properties("postId", p -> p.keyword(k -> k));
                mappings.properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("content", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("status", p -> p.keyword(k -> k));
                mappings.properties("createdAt", p -> p.date(d -> d));
            });
            createIndexIfAbsent(SearchIndexName.PRODUCT, mappings -> {
                mappings.properties("productId", p -> p.keyword(k -> k));
                mappings.properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("description", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("tradeLocation", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                mappings.properties("category", p -> p.keyword(k -> k));
                mappings.properties("status", p -> p.keyword(k -> k));
                mappings.properties("createdAt", p -> p.date(d -> d));
            });
            createIndexIfAbsent(SearchIndexName.USER, mappings -> {
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
        log.info("[SearchIndex] 创建索引 {}", indexName);
    }

    @Override
    public void recreate(String indexName) {
        try {
            if (client.indices().exists(request -> request.index(indexName)).value()) {
                client.indices().delete(request -> request.index(indexName));
            }
            ensureIndices();
        } catch (IOException e) {
            throw new IllegalStateException("ES 清空索引失败 index=" + indexName, e);
        }
    }

    @Override
    public SearchPageVO<String> search(String indexName, List<String> searchFields, List<String> publicStatuses,
                                       String keyword, int page, int pageSize) {
        if (keyword == null || keyword.isBlank()) {
            return new SearchPageVO<>(0, List.of());
        }
        try {
            SearchResponse<JsonData> response = client.search(buildSearchRequest(
                    indexName, searchFields, publicStatuses, keyword, page, pageSize), JsonData.class);
            List<String> idList = response.hits().hits().stream()
                    .map(hit -> hit.id())
                    .toList();
            long total = response.hits().total() == null ? 0 : response.hits().total().value();
            return new SearchPageVO<>(total, idList);
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
        List<FieldValue> statusValueList = publicStatuses.stream().map(FieldValue::of).toList();
        TermsQueryField statusField = TermsQueryField.of(terms -> terms.value(statusValueList));
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

    /**
     * 文档序列化用 Spring ObjectMapper（JavaTimeModule，createdAt 输出 ISO 字符串，ES date 默认格式可解析），
     * 经 JsonData 走原始 JSON 通道，避免 client 内部 mapper 序列化 POJO 的日期格式差异
     */
    @Override
    public <T> void bulkUpsert(String indexName, List<T> documents, Function<T, String> idOf) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder bulkRequest = new BulkRequest.Builder();
            for (T doc : documents) {
                ObjectNode jsonNode = objectMapper.valueToTree(doc);
                bulkRequest.operations(operation -> operation.index(indexOperation -> indexOperation
                        .index(indexName)
                        .id(idOf.apply(doc))
                        .document(JsonData.of(jsonNode))));
            }
            BulkResponse response = client.bulk(bulkRequest.build());
            if (response.errors()) {
                List<String> failedItemList = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.id() + ":" + item.error().reason())
                        .collect(Collectors.toList());
                throw new IllegalStateException("ES bulk 部分失败 index=" + indexName + "，failed=" + failedItemList);
            }
        } catch (IOException e) {
            throw new IllegalStateException("ES bulk 失败 index=" + indexName, e);
        }
    }

    @Override
    public void deleteDoc(String indexName, String documentId) {
        try {
            client.delete(request -> request.index(indexName).id(documentId).refresh(Refresh.True));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw e;
            }
            // 404：文档不存在，幂等忽略
        } catch (IOException e) {
            throw new IllegalStateException("ES 删除文档失败 index=" + indexName + ", id=" + documentId, e);
        }
    }
}
