package com.betterschool.domain.inventory.model.valobj;

import java.util.Objects;

/**
 * 一次兑换确认请求（spec §4.5）：定位（{@code awardId}）+ 业务身份（{@code bizType, bizId}）+ 数量，
 * 是与 {@link InventoryDeductRequestVO} 对称的完整入参信封——单独拿在手里即可执行，
 * 合并器的分片键、落库端口的台账定位都从它取。
 *
 * <p><b>awardId 为什么在对象里</b>（原 {@code ClaimItem} 设计的反转）：确认的 {@code awardId}
 * 虽然没有选择空间（就是预扣的那个账户），但拆在参数通道上会让「键与行」的形状在每层签名里
 * 重复出现；折进对象后只有一个来源，引擎入队与落库分组统一从 payload 取分片键。
 *
 * <p><b>为什么不带 {@code type}</b>：兑换确认只存在于实物段——虚拟段没有 {@code waiting}
 * （预扣直接 {@code surplus → issued}），也就没有确认动作。传一个恒为 {@code PHYSICAL} 的段参数
 * 只会多一条永远成立的分支（票 07 §4）；落库方法只打 {@code type = 'PHYSICAL'} 的台账行。
 *
 * @param awardId  奖品库存账户维度（= 预扣时的账户）：分片键与台账 {@code UPDATE} 的锚点
 * @param bizType  {@code InventoryBizTypes.LOTTERY_CLAIM}
 * @param bizId    抽奖记录 id（= {@code draw_record.record_id}），不是独立兑换单号
 * @param quantity 本次转移的份数；一笔确认 = 一份实物出库，恒 &gt; 0
 */
public final class InventoryClaimRequestVO {

    private final String awardId;
    private final String bizType;
    private final String bizId;
    private final long quantity;

    public InventoryClaimRequestVO(String awardId, String bizType, String bizId, long quantity) {
        this.awardId = awardId;
        this.bizType = bizType;
        this.bizId = bizId;
        this.quantity = quantity;
    }

    public String awardId() {
        return awardId;
    }

    public String bizType() {
        return bizType;
    }

    public String bizId() {
        return bizId;
    }

    public long quantity() {
        return quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryClaimRequestVO other)) return false;
        return quantity == other.quantity
                && Objects.equals(awardId, other.awardId)
                && Objects.equals(bizType, other.bizType)
                && Objects.equals(bizId, other.bizId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(awardId, bizType, bizId, quantity);
    }

    @Override
    public String toString() {
        return "InventoryClaimRequestVO[awardId=" + awardId + ", bizType=" + bizType + ", bizId=" + bizId
                + ", quantity=" + quantity + "]";
    }
}
