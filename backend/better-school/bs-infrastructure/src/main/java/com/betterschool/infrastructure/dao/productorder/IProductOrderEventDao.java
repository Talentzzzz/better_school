package com.betterschool.infrastructure.dao.productorder;

import org.apache.ibatis.annotations.Mapper;

/**
 * 商品托管订单状态流转事件 Mapper（product_order_event 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductOrderEventMapper.xml
 */
@Mapper
public interface IProductOrderEventDao {

    /** 订单状态流转事件落表（同事务，只插入） */
    int insertOrderEvent(com.betterschool.infrastructure.dao.productorder.po.ProductOrderEvent event);
}
