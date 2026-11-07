package com.betterschool.loadtest.inventory.api;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压测运维接口（不参与施压，压测期间只会被脚本调几次）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final InventoryProperties properties;

    public AdminController(AdminService adminService, InventoryProperties properties) {
        this.adminService = adminService;
        this.properties = properties;
    }

    /**
     * 重置一轮。每换一个策略/每跑一轮都要先调它。
     * <pre>
     *   curl -X POST "http://APP:8080/api/admin/reset?skuId=1001&stock=20000&buckets=1"
     * </pre>
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(required = false) Long skuId,
                                     @RequestParam(required = false) Long stock,
                                     @RequestParam(required = false) Integer buckets) {
        long resolvedSku = skuId == null ? properties.getDefaultSkuId() : skuId;
        long resolvedStock = stock == null ? properties.getDefaultStock() : stock;
        return adminService.reset(resolvedSku, resolvedStock, buckets);
    }

    /** 读一轮的账 / 一致性判定 */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(required = false) Long skuId) {
        long resolvedSku = skuId == null ? properties.getDefaultSkuId() : skuId;
        return adminService.stats(resolvedSku);
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("strategies", properties.getDefaultStrategy());
        out.put("serverTime", System.currentTimeMillis());
        return out;
    }
}
