package cn.inventory.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存扣减响应
 *
 * <p>刻意<b>不回带</b>剩余库存：扣减事务里再查一次库存会多一次往返、
 * 还会在热点行上多一次读，直接污染被测事务。要看库存请调 {@code GET /api/v1/stock/{skuId}}。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockDeductResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务单号 */
    private String bizNo;

    /** 仅 SUCCESS / DUPLICATE 为 true（DUPLICATE = 这笔业务此前已经扣成功，等价于成功） */
    private Boolean success;
}
