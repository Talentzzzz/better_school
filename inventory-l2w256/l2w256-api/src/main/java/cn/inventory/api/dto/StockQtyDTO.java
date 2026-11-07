package cn.inventory.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存查询响应（只读路径，不走合并器）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockQtyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long skuId;

    /** 剩余库存；SKU 不存在时为 null */
    private Integer qty;
}
