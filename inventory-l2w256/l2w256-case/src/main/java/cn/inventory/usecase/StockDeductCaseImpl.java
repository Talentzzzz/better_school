package cn.inventory.usecase;

import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.service.inventory.IStockInventoryService;
import cn.inventory.domain.stock.service.merge.IStockMergeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 库存扣减用例实现：单域编排，顺序调用域服务，不包跨域事务
 *
 * <p>{@link #reset} 的顺序是有讲究的：<b>先排空、再复位</b>。
 * 少了这一步，上一轮的尾批会在复位之后落库，把本轮的起始库存改掉——
 * 表现为「明明 reset 到 10 万，跑完却少了几百件」，整轮压测数据作废。
 */
@Slf4j
@Service
public class StockDeductCaseImpl implements IStockDeductCase {

    private final IStockMergeService stockMergeService;
    private final IStockInventoryService stockInventoryService;

    public StockDeductCaseImpl(IStockMergeService stockMergeService,
                               IStockInventoryService stockInventoryService) {
        this.stockMergeService = stockMergeService;
        this.stockInventoryService = stockInventoryService;
    }

    @Override
    public DeductResultVO deduct(DeductRequestVO request) {
        return stockMergeService.submit(request);
    }

    @Override
    public StockEntity query(long skuId) {
        return stockInventoryService.query(skuId);
    }

    @Override
    public void reset(long skuId, int qty) {
        if (!stockMergeService.awaitIdle()) {
            // 不阻断复位，但必须显眼：继续复位意味着在途批次会污染新一轮数据
            log.warn("复位前排空合并器超时，剩余积压 {}，本轮起始库存可能被在途请求继续修改",
                    stockMergeService.pendingCount());
        }
        stockInventoryService.reset(skuId, qty);
    }

    @Override
    public int mergePending() {
        return stockMergeService.pendingCount();
    }

    @Override
    public boolean awaitIdle() {
        return stockMergeService.awaitIdle();
    }
}
