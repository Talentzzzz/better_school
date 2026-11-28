package com.betterschool.infrastructure.dao.productinventory;

import com.betterschool.infrastructure.dao.productinventory.po.ProductInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品库存台账 Mapper（product_inventory 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductInventoryMapper.xml
 * （库存流水拆分至 IProductStockEventDao 及 ProductStockEventMapper.xml）
 * 扣减/回补均为条件 UPDATE：affected rows = 0 即库存不足/行缺失，由调用方裁决
 */
@Mapper
public interface IProductInventoryDao {

    int insert(ProductInventory productInventory);

    ProductInventory selectByProductId(@Param("productId") String productId);

    /** 批量回查（商品列表/详情展示"剩余件数"，SQL 层零 JOIN 的 Java 收口点） */
    List<ProductInventory> selectByProductIds(@Param("productIds") List<String> productIds);

    /** 下单扣减：remaining >= 扣减量才生效（防超卖唯一闸门），0 行 = 库存不足 */
    int deduct(@Param("productId") String productId, @Param("quantity") int quantity);

    /** 关单回补：上限钳在 quantity（LEAST 防重复回补越界），0 行 = 台账行缺失 */
    int restore(@Param("productId") String productId, @Param("quantity") int quantity);

    /** 卖家加货：总件数与剩余同涨，0 行 = 台账行缺失 */
    int restock(@Param("productId") String productId, @Param("quantity") int quantity);

    /** 清零剩余（卖家"标记已出"）：返回清零前的剩余值，供流水 delta 记账 */
    int clearRemainingReturnDelta(@Param("productId") String productId);

    /** 草稿改件数：quantity 与 remaining 一并覆盖（草稿期无订单，无回补越界问题） */
    int updateDraftQuantity(@Param("productId") String productId, @Param("quantity") int quantity);
}
