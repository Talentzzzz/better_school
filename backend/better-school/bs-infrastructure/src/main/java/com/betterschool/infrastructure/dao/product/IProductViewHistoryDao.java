package com.betterschool.infrastructure.dao.product;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品浏览 UV 明细表 Mapper（product_view_history 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductViewHistoryMapper.xml
 */
@Mapper
public interface IProductViewHistoryDao {

    // ── 商品浏览 UV 三步（market-feed-uv 票 02，照帖子 user_view_history / post_view_count 先例）──
    // 一组一个事务：INSERT IGNORE 明细（影响行数 = 真正新增的 UV 数）→ 全组刷 viewed_at → 加速列 += 新增数

    /** 第一步：UV 明细批量写，(user_id, product_id) 主键撞键裁决重复浏览 */
    int insertProductViewHistory(@Param("productId") String productId, @Param("userIds") List<String> userIdList);

    /** 第二步：刷新本组全部键的浏览时刻（含刚插入的行）——重复浏览的唯一可见效果 */
    int touchProductViewedAt(@Param("productId") String productId, @Param("userIds") List<String> userIdList);

    /** 当前用户浏览过的商品 id 集合（推荐列表已读灰显批量回显，走 (user_id, product_id) 主键前缀） */
    List<String> selectViewedProductIds(@Param("userId") String userId, @Param("productIds") List<String> productIdList);
}
