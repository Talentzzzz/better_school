package cn.inventory.domain.stock.service.inventory;

import cn.inventory.domain.stock.adapter.repository.IStockRepository;
import cn.inventory.domain.stock.model.entity.StockEntity;
import org.springframework.stereotype.Service;

/**
 * 库存查询 / 复位领域服务：只调一次仓储方法，事务边界由仓储持有
 */
@Service
public class StockInventoryServiceImpl implements IStockInventoryService {

    private final IStockRepository stockRepository;

    public StockInventoryServiceImpl(IStockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    public StockEntity query(long skuId) {
        return stockRepository.query(skuId);
    }

    @Override
    public void reset(long skuId, int qty) {
        stockRepository.reset(skuId, qty);
    }
}
