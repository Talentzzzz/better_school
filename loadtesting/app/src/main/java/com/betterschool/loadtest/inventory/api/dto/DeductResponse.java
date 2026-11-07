package com.betterschool.loadtest.inventory.api.dto;

/**
 * 扣减响应。业务失败（卖完）也是 HTTP 200，accepted=false + reason=SOLD_OUT，
 * 这样 JMeter 的「错误率」才只统计真正的技术故障。
 */
public record DeductResponse(boolean accepted, String reason, String strategy, String orderNo) {
}
