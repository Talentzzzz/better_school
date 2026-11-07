package com.betterschool.loadtest.inventory.api;

import com.betterschool.loadtest.inventory.api.dto.DeductRequest;
import com.betterschool.loadtest.inventory.api.dto.DeductResponse;
import com.betterschool.loadtest.inventory.config.InventoryProperties;
import com.betterschool.loadtest.inventory.strategy.DeductResult;
import com.betterschool.loadtest.inventory.strategy.DeductStrategy;
import com.betterschool.loadtest.inventory.strategy.StrategyRegistry;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 压测入口。三种策略同一个出参结构，JMeter 只改一个 strategy 字段就能横向对比。
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final StrategyRegistry registry;
    private final InventoryProperties properties;

    public InventoryController(StrategyRegistry registry, InventoryProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /** POST /api/inventory/deduct  {"strategy":"merge","skuId":1001,"qty":1,"orderNo":"uuid"} */
    @PostMapping("/deduct")
    public DeductResponse deduct(@RequestBody(required = false) DeductRequest request) {
        DeductRequest req = request == null ? new DeductRequest(null, null, null, null) : request;
        return doDeduct(
                req.strategy() == null ? properties.getDefaultStrategy() : req.strategy(),
                req.skuId() == null ? properties.getDefaultSkuId() : req.skuId(),
                req.qty() == null ? 1 : req.qty(),
                req.orderNo());
    }

    /** POST /api/inventory/merge/deduct —— 等价写法，方便 curl 手工验 */
    @PostMapping("/{strategy}/deduct")
    public DeductResponse deductByPath(@PathVariable("strategy") String strategy,
                                       @RequestBody(required = false) DeductRequest request) {
        DeductRequest req = request == null ? new DeductRequest(null, null, null, null) : request;
        return doDeduct(strategy,
                req.skuId() == null ? properties.getDefaultSkuId() : req.skuId(),
                req.qty() == null ? 1 : req.qty(),
                req.orderNo());
    }

    private DeductResponse doDeduct(String strategyName, long skuId, int qty, String orderNo) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty 必须大于 0");
        }
        DeductStrategy strategy = registry.get(strategyName);
        String bizNo = (orderNo == null || orderNo.isBlank()) ? UUID.randomUUID().toString() : orderNo;
        DeductResult result = strategy.deduct(skuId, qty, bizNo);
        return new DeductResponse(result.accepted(), result.reason(), strategyName, bizNo);
    }
}
