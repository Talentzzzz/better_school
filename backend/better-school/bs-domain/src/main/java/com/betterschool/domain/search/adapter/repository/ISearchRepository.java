package com.betterschool.domain.search.adapter.repository;

import com.betterschool.domain.search.model.valobj.ErrandSearchDocVO;
import com.betterschool.domain.search.model.valobj.PostSearchDocVO;
import com.betterschool.domain.search.model.valobj.ProductSearchDocVO;
import com.betterschool.domain.search.model.valobj.SearchPageVO;
import com.betterschool.domain.search.model.valobj.UserSearchDocVO;

import java.util.List;

/**
 * 搜索仓储接口（基础设施层以 Elasticsearch 实现）：
 * 索引只存 id + 检索/过滤字段，展示字段由调用方按 id 从 MySQL 反查组装
 */
public interface ISearchRepository {

    /** 幂等创建索引与 mapping（ik_max_word 索引分词 / ik_smart 搜索分词） */
    void ensureIndices();

    /** 全量重建第一步：清空指定实体索引（类型：post/product/errand/user） */
    void clearIndex(String entityType);

    /** 相关度倒序分页搜索，返回命中 id 列表与总数（近似值）；业务 id 统一是 UUID 字符串 */
    SearchPageVO<String> searchPosts(String keyword, int page, int pageSize);

    SearchPageVO<String> searchProducts(String keyword, int page, int pageSize);

    SearchPageVO<String> searchErrands(String keyword, int page, int pageSize);

    SearchPageVO<String> searchUsers(String keyword, int page, int pageSize);

    void bulkUpsertPostDocs(List<PostSearchDocVO> docs);

    void bulkUpsertProductDocs(List<ProductSearchDocVO> docs);

    void bulkUpsertErrandDocs(List<ErrandSearchDocVO> docs);

    void bulkUpsertUserDocs(List<UserSearchDocVO> docs);

    void deletePostDoc(String postId);

    void deleteProductDoc(String productId);

    void deleteErrandDoc(String orderId);

    void deleteUserDoc(String userId);
}
