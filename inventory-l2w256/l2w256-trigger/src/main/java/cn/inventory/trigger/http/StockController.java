package cn.inventory.trigger.http;

import cn.inventory.api.IStockService;
import cn.inventory.api.dto.StockDeductRequestDTO;
import cn.inventory.api.dto.StockDeductResponseDTO;
import cn.inventory.api.dto.StockMetricsDTO;
import cn.inventory.api.dto.StockQtyDTO;
import cn.inventory.api.dto.StockResetDTO;
import cn.inventory.api.response.Response;
import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.types.enums.ResponseCode;
import cn.inventory.usecase.IStockDeductCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存扣减 HTTP 入口
 *
 * <p>只做三件事：协议转换（DTO ↔ VO）、结果码映射、计数。
 * 任何业务判断都不许下沉到这里——这一层将来会被换成 dubbo provider，逻辑留在 case/domain 才能复用。
 *
 * <p>HTTP 状态码恒 200，业务结果走 {@code code}；异常统一由
 * {@link GlobalExceptionHandler} 兜，所以这里看不到 try/catch。
 */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController implements IStockService {

    private final IStockDeductCase stockDeductCase;
    private final DeductMetrics deductMetrics;

    public StockController(IStockDeductCase stockDeductCase, DeductMetrics deductMetrics) {
        this.stockDeductCase = stockDeductCase;
        this.deductMetrics = deductMetrics;
    }

    @PostMapping("/deduct")
    @Override
    public Response<StockDeductResponseDTO> deduct(@Valid @RequestBody StockDeductRequestDTO request) {
        DeductRequestVO requestVO = new DeductRequestVO(request.getSkuId(), request.getQty(), request.getBizNo());
        DeductResultVO resultVO = stockDeductCase.deduct(requestVO);

        deductMetrics.record(resultVO.getStatus());
        StockDeductResponseDTO responseDTO = new StockDeductResponseDTO(resultVO.getBizNo(), resultVO.isSuccess());

        return switch (resultVO.getStatus()) {
            case SUCCESS -> Response.success(responseDTO);
            // 幂等命中：业务上算成功（这笔单确实扣过了），但必须让调用方知道「本次没有真的扣」
            case DUPLICATE -> Response.success(ResponseCode.DUPLICATE.getCode(),
                    ResponseCode.DUPLICATE.getInfo(), responseDTO);
            case INSUFFICIENT -> Response.success(ResponseCode.INSUFFICIENT.getCode(),
                    ResponseCode.INSUFFICIENT.getInfo(), responseDTO);
            // 结果未知：文案必须是「处理中」，写成「扣减失败」会诱发用户重复下单
            case TIMEOUT_UNKNOWN -> Response.success(ResponseCode.MERGE_PROCESSING.getCode(),
                    ResponseCode.MERGE_PROCESSING.getInfo(), responseDTO);
            case REJECTED -> Response.success(ResponseCode.QUEUE_REJECTED.getCode(),
                    ResponseCode.QUEUE_REJECTED.getInfo(), responseDTO);
            case ERROR -> Response.fail(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo());
        };
    }

    @GetMapping("/{skuId}")
    @Override
    public Response<StockQtyDTO> query(@PathVariable("skuId") Long skuId) {
        StockEntity stock = stockDeductCase.query(skuId);
        return Response.success(new StockQtyDTO(stock.getSkuId(), stock.getQty()));
    }

    @PostMapping("/reset")
    @Override
    public Response<Void> reset(@Valid @RequestBody StockResetDTO request) {
        // 内部会先排空合并器，再复位库存与流水（避免尾批污染下一轮）
        stockDeductCase.reset(request.getSkuId(), request.getQty());
        return Response.success(null);
    }

    @GetMapping("/metrics")
    @Override
    public Response<StockMetricsDTO> metrics() {
        return Response.success(new StockMetricsDTO(
                deductMetrics.snapshot(), deductMetrics.errorSnapshot(), stockDeductCase.mergePending()));
    }

    @PostMapping("/metrics/reset")
    @Override
    public Response<Void> resetMetrics() {
        deductMetrics.clear();
        return Response.success(null);
    }

    @GetMapping("/health")
    @Override
    public Response<Void> health() {
        return Response.success(null);
    }
}
