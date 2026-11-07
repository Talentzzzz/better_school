package cn.inventory.api;

import cn.inventory.api.dto.StockDeductRequestDTO;
import cn.inventory.api.dto.StockDeductResponseDTO;
import cn.inventory.api.dto.StockMetricsDTO;
import cn.inventory.api.dto.StockQtyDTO;
import cn.inventory.api.dto.StockResetDTO;
import cn.inventory.api.response.Response;

/**
 * 库存扣减对外契约
 *
 * <p>这一层是「和协议无关」的：现在由 {@code StockController} 用 HTTP 实现，
 * 后续要对外暴露 dubbo，直接让 provider 实现同一个接口即可，DTO 不用改一行。
 * 因此这里<b>不出现</b>任何 {@code @RequestBody} / {@code @PathVariable} / MVC 类型。
 */
public interface IStockService {

    /** 扣减库存（L2W256 合并扣减） */
    Response<StockDeductResponseDTO> deduct(StockDeductRequestDTO request);

    /** 查询剩余库存（SKU 不存在时 qty 为 null） */
    Response<StockQtyDTO> query(Long skuId);

    /** 重置库存并清空流水 */
    Response<Void> reset(StockResetDTO request);

    /** 运行期指标快照 */
    Response<StockMetricsDTO> metrics();

    /** 清零指标 */
    Response<Void> resetMetrics();

    /** 存活探针 */
    Response<Void> health();
}
