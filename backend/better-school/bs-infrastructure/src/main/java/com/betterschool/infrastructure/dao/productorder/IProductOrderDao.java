package com.betterschool.infrastructure.dao.productorder;

import com.betterschool.infrastructure.dao.productorder.po.ProductOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品托管订单 Mapper（product_order 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductOrderMapper.xml
 * （状态流转事件拆分至 IProductOrderEventDao 及 ProductOrderEventMapper.xml）
 */
@Mapper
public interface IProductOrderDao {

    int insert(ProductOrder order);

    ProductOrder selectById(@Param("orderId") String orderId);

    int payById(@Param("orderId") String orderId, @Param("buyerId") String buyerId);

    int markDeliveredById(@Param("orderId") String orderId, @Param("sellerId") String sellerId);

    int confirmById(@Param("orderId") String orderId, @Param("buyerId") String buyerId);

    int cancelById(@Param("orderId") String orderId, @Param("buyerId") String buyerId);

    int cancelPendingPayment(@Param("orderId") String orderId);

    List<ProductOrder> selectMineByBuyer(@Param("userId") String userId, @Param("offset") int offset,
                                           @Param("limit") int limit);

    long countMineByBuyer(@Param("userId") String userId);

    List<ProductOrder> selectMineBySeller(@Param("userId") String userId, @Param("offset") int offset,
                                            @Param("limit") int limit);

    long countMineBySeller(@Param("userId") String userId);
}
