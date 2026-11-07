package cn.inventory.usecase;

import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;

/**
 * 库存扣减用例（编排层）
 *
 * <p>当前只有库存一个域，编排很薄；但「复位前排空合并器」这一步注定落在这一层——
 * 它同时触碰「合并器」和「库存写」，属于两个能力的顺序编排，不属于任何一个域的内部逻辑。
 * 后续接入订单域（下单 → 扣库存 → 写订单）时，跨域顺序调用全部加在这一层，域层不动。
 */
public interface IStockDeductCase {

    /** 扣减库存 */
    DeductResultVO deduct(DeductRequestVO request);

    /** 查库存实体（库存行不存在时 {@code qty} 为 null） */
    StockEntity query(long skuId);

    /** 复位库存（先排空合并器，再复位） */
    void reset(long skuId, int qty);

    /** 合并器当前积压数（运维观测用） */
    int mergePending();

    /** 等待合并器排空（预热 / 复位 / 停机用；等待上限取配置的 drain-timeout-ms） */
    boolean awaitIdle();
}
