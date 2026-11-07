package cn.inventory.domain.stock.service.inventory;

import cn.inventory.domain.stock.model.entity.StockEntity;

/**
 * 库存查询与复位（非热点路径：走的是普通读/批量删，不参与合并）
 */
public interface IStockInventoryService {

    /**
     * 查库存实体。
     *
     * @return 库存实体；库存行不存在时 {@code qty} 为 null
     */
    StockEntity query(long skuId);

    /**
     * 复位指定 SKU 的库存并清空其流水。
     * <p><b>调用前必须先排空合并器</b>（见 case 层），否则在途批次会在复位之后落库。
     */
    void reset(long skuId, int qty);
}
