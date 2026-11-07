package com.betterschool.loadtest.inventory.strategy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StrategyRegistry {

    private final Map<String, DeductStrategy> strategies = new LinkedHashMap<>();

    public StrategyRegistry(List<DeductStrategy> all) {
        for (DeductStrategy strategy : all) {
            strategies.put(strategy.name(), strategy);
        }
    }

    public DeductStrategy get(String name) {
        DeductStrategy strategy = strategies.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("未知策略: " + name + "，可选值: " + strategies.keySet());
        }
        return strategy;
    }

    public List<String> names() {
        return List.copyOf(strategies.keySet());
    }
}
