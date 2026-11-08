package com.betterschool.infrastructure.dao.product;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品收藏 Mapper（product_favorite 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductFavoriteMapper.xml
 */
@Mapper
public interface IProductFavoriteDao {

    int insertFavorite(@Param("productId") String productId, @Param("userId") String userId);

    int deleteFavorite(@Param("productId") String productId, @Param("userId") String userId);

    int existsFavorite(@Param("productId") String productId, @Param("userId") String userId);

    long countFavorites(@Param("productId") String productId);

    /** 我的收藏：收藏关系行主查（product_favorite 单表，物理 id DESC = 收藏先后倒序）；商品行与可见性仓储拼装 */
    List<String> selectFavoriteProductIdsByUser(@Param("userId") String userId);
}
