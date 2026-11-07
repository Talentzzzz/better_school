package com.betterschool.loadtest.inventory.strategy;

/**
 * 三种策略的统一入口：同样的参数、同样的返回结构，只有内部实现不同。
 */
public interface DeductStrategy {

    /** 策略名，同时是接口路径上的 {strategy} 段 */
    String name();

    DeductResult deduct(long skuId, int qty, String orderNo);
}
