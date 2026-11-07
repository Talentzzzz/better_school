package cn.inventory.domain.stock.model.valobj;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置校验：配错了要在启动瞬间炸掉，而不是压测跑到一半才发现
 */
class MergeConfigVOTest {

    private static MergeConfigVO valid() {
        return new MergeConfigVO(256, 5, 200, 100, 10_000, 20, true, 5_000);
    }

    @Test
    @DisplayName("合法参数可以直接构造")
    void validConfig() {
        assertDoesNotThrow(MergeConfigVOTest::valid);
    }

    @Test
    @DisplayName("分片数不是 2 的幂 → 构造失败（路由用 skuId & mask，非 2 的幂会越界）")
    void shardCountMustBePowerOfTwo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new MergeConfigVO(255, 5, 200, 100, 10_000, 20, true, 5_000));
        assertTrue(e.getMessage().contains("2 的幂"), e.getMessage());
    }

    @Test
    @DisplayName("等待上限必须大于窗口，否则请求必然先超时后落库")
    void waitTimeoutMustExceedWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfigVO(256, 100, 200, 100, 10_000, 20, true, 5_000));
    }

    @Test
    @DisplayName("缓冲区容量必须 >= 批上限，否则永远攒不满一批就被拒绝")
    void capacityMustCoverBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfigVO(256, 5, 200, 100, 100, 20, true, 5_000));
    }

    @Test
    @DisplayName("路由：同一 SKU 恒落同一分片，且下标不越界")
    void shardRouting() {
        MergeConfigVO config = valid();
        assertEquals(1001 & 255, config.shardOf(1001L));
        assertEquals(config.shardOf(1001L), config.shardOf(1001L));
        // 负 skuId 也不能算出负下标（mask 恒正）
        assertTrue(config.shardOf(-1L) >= 0 && config.shardOf(-1L) < 256);
    }
}
