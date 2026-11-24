package com.betterschool.domain.search.service;

import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.search.model.valobj.SearchPageVO;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.user.model.entity.UserEntity;

/**
 * 搜索服务：同步事件消费（handleSync）+ 公共检索（ES 得 id → 域仓储反查组装，保持相关度顺序）
 */
public interface ISearchService {

    /** 索引同步消息消费：按 id 反查 MySQL 构建文档；实体不存在或非公开状态 → 删文档（幂等自愈） */
    void handleSync(SearchSyncMessage message);

    SearchPageVO<PostEntity> searchPosts(String keyword, int page, int pageSize);

    SearchPageVO<ProductEntity> searchProducts(String keyword, int page, int pageSize);

    SearchPageVO<UserEntity> searchUsers(String keyword, int page, int pageSize);

    /** 全量重建（XXL-Job 触发）：清空索引后按库中当前公开数据分批回填 */
    void rebuildAllIndices();
}
