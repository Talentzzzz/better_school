package com.betterschool.domain.award.model.valobj;

import com.betterschool.types.award.AwardInventoryTypeEnum;

import java.util.Objects;

/**
 * 一次预扣请求：把 {@code (type, awardId, quantity, bizType, bizId)} 五个散参收成一个值对象
 * （spec §7.1），让「主档 + 兜底」两次预扣可以并排传参、也让账户类型可以不同（实物奖品 → 虚拟奖品）。
 * <p>
 * {@code awardId} 是奖品库存账户的维度（{@code award_inventory} 按 {@code award_id} 1:1），
 * 不存在的账户即「未投放」。
 *
 * @param type    账户类型（PHYSICAL / VIRTUAL）：统一台账按它分支两条 UPDATE
 * @param awardId 奖品位 id（= 奖品库存账户维度）：预扣落库与合并分片都锚在它上
 * @param bizType {@code AwardBizTypes} 的取值（抽奖域不认识库存列，由奖品侧端口实现填）
 * @param bizId   业务 ID（抽奖 = {@code draw_record.record_id}）：明细唯一键的去重锚点
 */
public final class AwardDeductRequestVO {

    private final AwardInventoryTypeEnum type;
    private final String awardId;
    private final long quantity;
    private final String bizType;
    private final String bizId;

    public AwardDeductRequestVO(AwardInventoryTypeEnum awardInventoryType,
                                    String awardId,
                                    long quantity,
                                    String bizType,
                                    String bizId) {
        this.type = awardInventoryType;
        this.awardId = awardId;
        this.quantity = quantity;
        this.bizType = bizType;
        this.bizId = bizId;
    }

    public AwardInventoryTypeEnum type() {
        return type;
    }

    public String awardId() {
        return awardId;
    }

    public long quantity() {
        return quantity;
    }

    public String bizType() {
        return bizType;
    }

    public String bizId() {
        return bizId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AwardDeductRequestVO awardDeductRequestVO)) return false;
        return quantity == awardDeductRequestVO.quantity
                && Objects.equals(type, awardDeductRequestVO.type)
                && Objects.equals(awardId, awardDeductRequestVO.awardId)
                && Objects.equals(bizType, awardDeductRequestVO.bizType)
                && Objects.equals(bizId, awardDeductRequestVO.bizId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, awardId, quantity, bizType, bizId);
    }

    @Override
    public String toString() {
        return "AwardDeductRequestVO[type=" + type + ", awardId=" + awardId + ", quantity=" + quantity
                + ", bizType=" + bizType + ", bizId=" + bizId + "]";
    }
}
