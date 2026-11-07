package com.betterschool.loadtest.inventory.api.dto;

/**
 * 扣减请求。strategy 不传时取 inventory.default-strategy；orderNo 不传时服务端生成 UUID。
 * 压测里 orderNo 必须唯一（明细表 / 订单表都有唯一键），JMeter 用 ${__UUID()} 生成。
 */
public record DeductRequest(String strategy, Long skuId, Integer qty, String orderNo) {
}
