package cn.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存重置请求（两轮压测之间 / 环境初始化使用）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockResetDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "skuId 不能为空")
    @Min(value = 1, message = "skuId 必须为正数")
    private Long skuId;

    @NotNull(message = "qty 不能为空")
    @Min(value = 0, message = "qty 不能为负")
    private Integer qty;
}
