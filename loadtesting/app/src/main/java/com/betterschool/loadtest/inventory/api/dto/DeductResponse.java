package com.betterschool.loadtest.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 扣减响应。业务失败（卖完）也是 HTTP 200，accepted=false + reason=SOLD_OUT，
 * 这样 JMeter 的「错误率」才只统计真正的技术故障。
 */
public final class DeductResponse {

    private final boolean accepted;
    private final String reason;
    private final String strategy;
    private final String orderNo;

    @JsonCreator
    public DeductResponse(@JsonProperty("accepted") boolean accepted,
                          @JsonProperty("reason") String reason,
                          @JsonProperty("strategy") String strategy,
                          @JsonProperty("orderNo") String orderNo) {
        this.accepted = accepted;
        this.reason = reason;
        this.strategy = strategy;
        this.orderNo = orderNo;
    }

    @JsonProperty("accepted")
    public boolean accepted() {
        return accepted;
    }

    @JsonProperty("reason")
    public String reason() {
        return reason;
    }

    @JsonProperty("strategy")
    public String strategy() {
        return strategy;
    }

    @JsonProperty("orderNo")
    public String orderNo() {
        return orderNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeductResponse other)) return false;
        return accepted == other.accepted
                && Objects.equals(reason, other.reason)
                && Objects.equals(strategy, other.strategy)
                && Objects.equals(orderNo, other.orderNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accepted, reason, strategy, orderNo);
    }

    @Override
    public String toString() {
        return "DeductResponse[accepted=" + accepted + ", reason=" + reason + ", strategy=" + strategy
                + ", orderNo=" + orderNo + "]";
    }
}
