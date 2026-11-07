package cn.inventory.domain.stock.model.valobj;

import cn.inventory.types.enums.DeductStatus;
import lombok.Getter;
import lombok.ToString;

/**
 * 扣减结果值对象
 *
 * <p>刻意<b>不带</b> remainingQty：合并扣减的落库事务里再查一次库存会多一次往返、
 * 还会在热点行上多一次读，直接污染被测事务。需要库存请走查询接口。
 */
@Getter
@ToString
public class DeductResultVO {

    /** 业务单号（原样回带，便于调用方对齐） */
    private final String bizNo;

    /** 结果状态 */
    private final DeductStatus status;

    public DeductResultVO(String bizNo, DeductStatus status) {
        this.bizNo = bizNo;
        this.status = status;
    }

    public static DeductResultVO of(String bizNo, DeductStatus status) {
        return new DeductResultVO(bizNo, status);
    }

    /** 业务上是否算「成了」：幂等命中同样算成功 */
    public boolean isSuccess() {
        return status == DeductStatus.SUCCESS || status == DeductStatus.DUPLICATE;
    }
}
