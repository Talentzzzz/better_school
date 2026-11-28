package com.betterschool.infrastructure.dao.productorder;

import com.betterschool.infrastructure.dao.productorder.po.ProductOrderEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 商品托管订单状态流转事件 Mapper（product_order_event 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductOrderEventMapper.xml
 */
@Mapper
public interface IProductOrderEventDao {

    /** 订单状态流转事件落表（同事务，只插入） */
    int insertOrderEvent(ProductOrderEvent productOrderEvent);

    /** 订单最新一条事件（id 递增即状态变更顺序）：孤儿扫描的已支付取消判据事实源 */
    ProductOrderEvent selectLatestEvent(@Param("orderId") String orderId);
}
