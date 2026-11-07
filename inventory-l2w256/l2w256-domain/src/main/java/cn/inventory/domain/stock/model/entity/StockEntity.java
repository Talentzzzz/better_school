package cn.inventory.domain.stock.model.entity;

import lombok.Getter;
import lombok.ToString;

/**
 * 库存实体（聚合根）
 *
 * <p><b>为什么是实体不是值对象</b>：身份是 {@code skuId}，状态是 {@code qty}。
 * 两个「剩余 100 件」但 skuId 不同的库存不是同一个东西，所以谈不上可互换，
 * 也就不能用值对象的「按值相等」来理解它。
 *
 * <p><b>为什么没有扣减方法</b>：并发下的「不扣成负数」只能由 DB 的条件 UPDATE
 * （{@code WHERE qty >= n}）兜底，应用层任何内存判断都会被并发穿透。
 * 所以实体在这里承载的是<b>状态表达</b>与<b>判据</b>，落库动作留在仓储。
 */
@Getter
@ToString
public class StockEntity {

    /** SKU 编号（身份） */
    private final Long skuId;

    /** 剩余库存；库存行不存在时为 null */
    private final Integer qty;

    public StockEntity(Long skuId, Integer qty) {
        if (skuId == null) {
            throw new IllegalArgumentException("skuId 不能为空");
        }
        this.skuId = skuId;
        this.qty = qty;
    }

    /**
     * 库存是否够扣 {@code requiredQty} 件。
     *
     * <p>库存行不存在（{@code qty == null}）视为不够：再怎么重试也不可能扣成功。
     * 售罄短路就是靠这个判据一次判定整批，省掉二分拆批的 399 次事务。
     */
    public boolean isEnough(int requiredQty) {
        return qty != null && qty >= requiredQty;
    }
}
