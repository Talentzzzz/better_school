package com.betterschool.domain.search.adapter.port;

import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.user.model.entity.UserEntity;

import java.util.List;

/**
 * 搜索同步只读查询端口（search 域的跨域只读声明）：事件同步与全量重建时按 id 反查各域实体。
 * 一个端口带多方法（按被读实体分段，不按调用点拆细），实现落
 * `infrastructure/adapter/port/search/SearchQueryPort`，实现内只读各域读路径。
 *
 * <p>端口只取实体，不搬任何判据：「哪些状态可进索引 / 不进索引即删文档」的判定留在
 * {@code SearchServiceImpl}；返回的实体类型是各域 `model.entity`（与 chat 侧
 * {@code CardRefQueryPort} 取他域状态枚举同口径），实现内只读他域仓储，故由 infra 承担翻译。
 */
public interface ISearchQueryPort {

    // ---------- 帖子（community） ----------

    /** 单个帖子；不存在返回 null */
    PostEntity findPost(String postId);

    /** 按 id 列表查询（不分状态；返回顺序与入参无关，调用方按 id 重排） */
    List<PostEntity> listPostsByIds(List<String> postIds);

    /** 帖子索引全量重建的游标页：post_id 字典序 > lastPostId 取 limit 条（业务 id 是 UUID 字符串） */
    List<String> listPostIdsAfter(String lastPostId, int limit);

    // ---------- 商品（trade） ----------

    /** 单个商品；不存在返回 null（业务 id 是 UUID 字符串） */
    ProductEntity findProduct(String productId);

    /** 按 id 列表查询（不分状态；返回顺序与入参无关，调用方按 id 重排） */
    List<ProductEntity> listProductsByIds(List<String> productIds);

    /** 商品索引全量重建的游标页：product_id 字典序 > lastProductId 取 limit 条（业务 id 是 UUID 字符串） */
    List<String> listProductIdsAfter(String lastProductId, int limit);

    // ---------- 跑腿单（errand） ----------

    /** 单个跑腿单；不存在返回 null（业务 id 是 UUID 字符串） */
    ErrandOrderEntity findErrand(String orderId);

    /** 按 id 列表查询（不分状态；返回顺序与入参无关，调用方按 id 重排） */
    List<ErrandOrderEntity> listErrandsByIds(List<String> orderIds);

    /** 跑腿索引全量重建的游标页：errand_order_id 字典序 > lastOrderId 取 limit 条（业务 id 是 UUID 字符串） */
    List<String> listErrandIdsAfter(String lastOrderId, int limit);

    // ---------- 用户（user） ----------

    /** 单个用户；不存在返回 null */
    UserEntity findUser(String userId);

    /** 按 id 列表查询（不分状态；返回顺序与入参无关，调用方按 id 重排） */
    List<UserEntity> listUsersByIds(List<String> userIds);

    /** 用户索引全量重建的游标页：id > lastUserId 按 id 升序取 limit 条 */
    List<String> listUserIdsAfter(String lastUserId, int limit);
}
