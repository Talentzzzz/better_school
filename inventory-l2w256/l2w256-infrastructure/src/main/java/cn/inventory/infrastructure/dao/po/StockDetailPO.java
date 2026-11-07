package cn.inventory.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 扣减流水表 PO（{@code biz_no} 唯一索引即幂等键）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockDetailPO {

    private Long id;

    /** 业务单号（幂等键） */
    private String bizNo;

    private Long skuId;

    private Integer qty;

    /** 1 = 扣减成功 */
    private Integer status;

    private LocalDateTime createdAt;
}
