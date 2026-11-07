package cn.inventory.domain.stock.model.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存实体：售罄判据（批量 UPDATE affected=0 之后，整批该不该直接判死全靠它）
 */
class StockEntityTest {

    @Test
    @DisplayName("边界：剩余量恰好等于请求量要算够（qty >= requiredQty）")
    void enoughOnBoundary() {
        StockEntity stock = new StockEntity(1001L, 5);
        assertTrue(stock.isEnough(5));
        assertTrue(stock.isEnough(1));
        assertFalse(stock.isEnough(6));
    }

    @Test
    @DisplayName("库存行不存在（qty 为 null）：任何数量都判不够，不去二分拆批")
    void absentRowIsNeverEnough() {
        StockEntity absent = new StockEntity(1001L, null);
        assertFalse(absent.isEnough(0));
        assertFalse(absent.isEnough(1));
    }

    @Test
    @DisplayName("没有身份的库存不是库存：skuId 为空直接拒绝")
    void skuIdIsIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new StockEntity(null, 1));
    }
}
