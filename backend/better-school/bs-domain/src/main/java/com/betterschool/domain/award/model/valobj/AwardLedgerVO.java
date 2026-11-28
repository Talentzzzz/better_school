package com.betterschool.domain.award.model.valobj;

import java.util.Objects;

/**
 * 奖品库存台账行的公共形状（{@code award_inventory}）：域服务只关心「在不在、开不开、账上多少」，
 * 账户的 {@code type}（PHYSICAL / VIRTUAL）由预扣请求携带，不从台账形状里读。
 * <p>
 * {@code waitingQty} 在虚拟账户恒 0（虚拟没有兑换确认，合成就直接进 {@code issued_qty}）；
 * 实物账户对账口径 {@code waiting + issued}。
 */
public final class AwardLedgerVO {

    private final String awardId;
    private final Long surplusQty;
    private final Long waitingQty;
    private final Long issuedQty;
    private final String state;

    public AwardLedgerVO(String awardId,
                         Long surplusQty,
                         Long waitingQty,
                         Long issuedQty,
                         String state) {
        this.awardId = awardId;
        this.surplusQty = surplusQty;
        this.waitingQty = waitingQty;
        this.issuedQty = issuedQty;
        this.state = state;
    }

    public String awardId() {
        return awardId;
    }

    public Long surplusQty() {
        return surplusQty;
    }

    public Long waitingQty() {
        return waitingQty;
    }

    public Long issuedQty() {
        return issuedQty;
    }

    public String state() {
        return state;
    }

    /** 台账是否可预扣：只有 {@code open} 能扣（{@code exhausted} / {@code closed} 都拒绝） */
    public boolean isOpen() {
        return "open".equals(state);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AwardLedgerVO awardLedgerVO)) return false;
        return Objects.equals(awardId, awardLedgerVO.awardId)
                && Objects.equals(surplusQty, awardLedgerVO.surplusQty)
                && Objects.equals(waitingQty, awardLedgerVO.waitingQty)
                && Objects.equals(issuedQty, awardLedgerVO.issuedQty)
                && Objects.equals(state, awardLedgerVO.state);
    }

    @Override
    public int hashCode() {
        return Objects.hash(awardId, surplusQty, waitingQty, issuedQty, state);
    }

    @Override
    public String toString() {
        return "AwardLedgerVO[awardId=" + awardId + ", surplusQty=" + surplusQty
                + ", waitingQty=" + waitingQty + ", issuedQty=" + issuedQty + ", state=" + state + "]";
    }
}
