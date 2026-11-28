package com.betterschool.infrastructure.dao.productinventory;

import com.betterschool.infrastructure.dao.productinventory.po.ProductStockEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品库存流水 Mapper（product_stock_event 单表，只插入不修改）：SQL 定义在 app 模块
 * resources/mybatis/mapper/ProductStockEventMapper.xml
 */
@Mapper
public interface IProductStockEventDao {

    /** 库存流水落表（与台账变更同事务，只插入） */
    int insertStockEvent(ProductStockEvent productStockEvent);
}
