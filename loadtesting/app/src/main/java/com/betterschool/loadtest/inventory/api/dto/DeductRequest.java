package com.betterschool.loadtest.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 扣减请求。strategy 不传时取 inventory.default-strategy；orderNo 不传时服务端生成 UUID。
 * 压测里 orderNo 必须唯一（明细表 / 订单表都有唯一键），JMeter 用 ${__UUID()} 生成。
 */
public final class DeductRequest {

    private final String strategy;
    private final Long skuId;
    private final Integer qty;
    private final String orderNo;

    @JsonCreator
    public DeductRequest(@JsonProperty("strategy") String strategy,
                         @JsonProperty("skuId") Long skuId,
                         @JsonProperty("qty") Integer qty,
                         @JsonProperty("orderNo") String orderNo) {
        this.strategy = strategy;
        this.skuId = skuId;
        this.qty = qty;
        this.orderNo = orderNo;
    }

    @JsonProperty("strategy")
    public String strategy() {
        return strategy;
    }

    @JsonProperty("skuId")
    public Long skuId() {
        return skuId;
    }

    @JsonProperty("qty")
    public Integer qty() {
        return qty;
    }

    @JsonProperty("orderNo")
    public String orderNo() {
        return orderNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeductRequest other)) return false;
        return Objects.equals(strategy, other.strategy)
                && Objects.equals(skuId, other.skuId)
                && Objects.equals(qty, other.qty)
                && Objects.equals(orderNo, other.orderNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(strategy, skuId, qty, orderNo);
    }

    @Override
    public String toString() {
        return "DeductRequest[strategy=" + strategy + ", skuId=" + skuId + ", qty=" + qty + ", orderNo=" + orderNo + "]";
    }
}
