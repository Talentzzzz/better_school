package com.betterschool.infrastructure.search;

import com.betterschool.types.common.SearchPageVO;

import java.util.List;
import java.util.function.Function;

/**
 * 倒排索引技术组件契约（Elasticsearch 实现）：只认索引名 / 检索字段 / 文档对象，
 * <b>不认识任何业务实体</b>——「哪个状态算公开可搜」由调用方（各域仓储）传参决定，
 * 判据留在业务域（backend/CLAUDE.md §1「技术能力一律走本域仓储」）。
 *
 * <p>这是 infrastructure 内部的技术组件接口（供测试替身替换），<b>不是 domain port</b>；
 * 各域仓储直接注入本组件，不为它开 domain 端口。
 */
public interface ISearchIndex {

    /** 全量重建的游标批大小：批 = 一次「拉批 + 批内反查 + 批量写入」 */
    int DEFAULT_REBUILD_BATCH_SIZE = 500;

    /** 幂等创建全部索引与 mapping（已存在则跳过；ik_max_word 索引分词 / ik_smart 搜索分词） */
    void ensureIndices();

    /** 清空并重建单个索引：先删后建，重建期间旧数据不可见，避免新旧混合 */
    void recreate(String indexName);

    /**
     * 关键词检索：multi_match 命中 + status 白名单过滤，返回命中 id（相关度倒序）与近似总数。
     * 不取 _source——展示字段由调用方按 id 回业务库反查，避免索引与库双写漂移
     */
    SearchPageVO<String> search(String indexName, List<String> searchFields, List<String> publicStatuses,
                                String keyword, int page, int pageSize);

    /** 批量写入（同 id 覆盖，走 bulk 通道）；documents 为空直接跳过 */
    <T> void bulkUpsert(String indexName, List<T> documents, Function<T, String> idOf);

    /** 删除单条文档（文档不存在按幂等忽略） */
    void deleteDoc(String indexName, String documentId);
}
