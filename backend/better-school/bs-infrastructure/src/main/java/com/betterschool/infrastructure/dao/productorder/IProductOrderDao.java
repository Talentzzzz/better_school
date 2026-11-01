package com.betterschool.infrastructure.dao.productorder;

import com.betterschool.infrastructure.dao.productorder.po.ProductOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品托管订单 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/ProductOrderMapper.xml
 */
@Mapper
public interface IProductOrderDao {

    int insert(ProductOrderPO orderPO);

    /** 业务 ID 回写：product_order_id = id（同事务，见 sql.md 主键口径） */
    int bindBizId(@Param("id") Long id);

    ProductOrderPO selectById(@Param("orderId") Long orderId);

    int payById(@Param("orderId") Long orderId, @Param("buyerId") String buyerId);

    int markDeliveredById(@Param("orderId") Long orderId, @Param("sellerId") String sellerId);

    int confirmById(@Param("orderId") Long orderId, @Param("buyerId") String buyerId);

    int cancelById(@Param("orderId") Long orderId, @Param("buyerId") String buyerId);

    int cancelPendingPayment(@Param("orderId") Long orderId);

    List<ProductOrderPO> selectMineByBuyer(@Param("userId") String userId, @Param("offset") int offset,
                                           @Param("limit") int limit);

    long countMineByBuyer(@Param("userId") String userId);

    List<ProductOrderPO> selectMineBySeller(@Param("userId") String userId, @Param("offset") int offset,
                                            @Param("limit") int limit);

    long countMineBySeller(@Param("userId") String userId);

    /** 订单状态流转事件落表（同事务，只插入） */
    int insertOrderEvent(com.betterschool.infrastructure.dao.productorder.po.ProductOrderEventPO eventPO);
}
