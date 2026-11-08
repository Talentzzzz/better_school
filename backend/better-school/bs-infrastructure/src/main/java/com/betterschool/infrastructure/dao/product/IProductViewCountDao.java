package com.betterschool.infrastructure.dao.product;

import com.betterschool.infrastructure.dao.product.po.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品浏览 UV 计数窄表 Mapper（product_view_count 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductViewCountMapper.xml
 */
@Mapper
public interface IProductViewCountDao {

    /** 浏览 UV 加速列批量读：按 id 集单表 IN 查询，仓储补 0 拼装（详情与列表共用） */
    List<Product> selectViewCountsByProductIds(@Param("productIds") List<String> productIdList);

    /** 第三步：加速列 upsert（行懒建 ODKU）；增量 = insertProductViewHistory 的影响行数 */
    int upsertProductViewCount(@Param("productId") String productId, @Param("delta") int delta);

    /** 浏览 UV 加速列校准收尾：清掉没有明细行的孤儿计数行（重投影由 {@link #recalibrateViewCounts} 完成） */
    int deleteViewCountsMissingHistory();

    /** 浏览 UV 加速列校准：product_view_count 重投影 = product_view_history 行数（ODKU 补缺行 / 刷漂移 / 幂等落回同值） */
    int recalibrateViewCounts();
}
