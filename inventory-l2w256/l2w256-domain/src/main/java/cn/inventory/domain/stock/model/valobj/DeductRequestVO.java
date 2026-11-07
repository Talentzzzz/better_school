package cn.inventory.domain.stock.model.valobj;

import lombok.Getter;
import lombok.ToString;

/**
 * 扣减请求值对象
 *
 * <p>字段全 final：请求一旦进入合并缓冲区就会被多个线程读，
 * 不可变能把「谁改了我的 skuId」这类事故从可能性上消灭。
 * 校验放在构造器里，缓冲区里不再出现「qty 为负」这种脏数据。
 */
@Getter
@ToString
public class DeductRequestVO {

    /** SKU 编号（分片路由键） */
    private final Long skuId;

    /** 扣减数量，必 &gt; 0 */
    private final Integer qty;

    /** 业务单号，幂等键（唯一裁决者是 {@code stock_detail.uk_biz_no}） */
    private final String bizNo;

    public DeductRequestVO(Long skuId, Integer qty, String bizNo) {
        if (skuId == null || bizNo == null || bizNo.isBlank()) {
            throw new IllegalArgumentException("skuId / bizNo 不能为空");
        }
        if (qty == null || qty <= 0) {
            throw new IllegalArgumentException("qty 必须 > 0，当前 " + qty);
        }
        this.skuId = skuId;
        this.qty = qty;
        this.bizNo = bizNo;
    }
}
