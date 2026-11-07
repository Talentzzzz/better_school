package cn.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存扣减请求
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockDeductRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "skuId 不能为空")
    @Min(value = 1, message = "skuId 必须为正数")
    private Long skuId;

    @NotNull(message = "qty 不能为空")
    @Min(value = 1, message = "qty 必须 >= 1")
    private Integer qty;

    /**
     * 业务单号（幂等键）。
     * <p>长度上限 64 与 {@code stock_detail.biz_no VARCHAR(64)} 对齐——超长会在 DB 层截断/报错，
     * 而幂等键一旦被截断，两笔不同业务就可能撞成同一笔。
     */
    @NotBlank(message = "bizNo 不能为空")
    @Size(max = 64, message = "bizNo 长度不能超过 64")
    private String bizNo;
}
