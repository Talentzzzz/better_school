package com.betterschool.domain.rebate.model.valobj;

import com.betterschool.types.rebate.RebateClaimApiState;
import com.betterschool.types.rebate.RebateClaimState;
import lombok.Getter;

/**
 * 一次行为返利领取的对外结果：API 状态 + 落库状态 + 金额快照。
 * <p>
 * {@code apiState} 只有 {@link RebateClaimApiState} 的四种取值；{@code persistedState} 只在真的落了
 * 领取行时非空（{@code CLAIMED} / {@code GRANTED}），{@code DENIED} / {@code NO_REBATE} / {@code PENDING}
 * 一律为 {@code null}，且两个金额恒为 0——这三种状态没有承诺过任何金额。
 * <p>
 * 已有领取行重放时 API 状态仍是 {@code CLAIMED}（本次行为确实已领取），{@code persistedState} 回真实值：
 * 尚未入账是 {@code CLAIMED}，入账完成是 {@code GRANTED}（由 {@link #granted} 构造）。
 */
@Getter
public class RebateClaimResultVO {

    private final RebateClaimApiState apiState;
    private final RebateClaimState persistedState;
    private final String claimId;
    /** 本次行为 ID（发帖 = postId），用于响应拼装 */
    private final String refId;
    private final int pointsValue;
    private final int drawCountValue;

    private RebateClaimResultVO(RebateClaimApiState apiState, RebateClaimState persistedState,
                                String claimId, String refId, int pointsValue, int drawCountValue) {
        this.apiState = apiState;
        this.persistedState = persistedState;
        this.claimId = claimId;
        this.refId = refId;
        this.pointsValue = pointsValue;
        this.drawCountValue = drawCountValue;
    }

    /** 本次行为抢到名额：领取行已落库，状态 CLAIMED，金额为领取快照 */
    public static RebateClaimResultVO claimed(String claimId, String refId, int pointsValue, int drawCountValue) {
        return new RebateClaimResultVO(RebateClaimApiState.CLAIMED, RebateClaimState.CLAIMED,
                claimId, refId, pointsValue, drawCountValue);
    }

    /** 幂等重放命中已入账的领取行：API 状态仍为 CLAIMED，落库状态为 GRANTED */
    public static RebateClaimResultVO granted(String claimId, String refId, int pointsValue, int drawCountValue) {
        return new RebateClaimResultVO(RebateClaimApiState.CLAIMED, RebateClaimState.GRANTED,
                claimId, refId, pointsValue, drawCountValue);
    }

    /** 当天名额已被该用户的其他行为领取（日限命中）：不落行、金额恒 0 */
    public static RebateClaimResultVO denied(String refId) {
        return new RebateClaimResultVO(RebateClaimApiState.DENIED, null, null, refId, 0, 0);
    }

    /** 无开放规则或快照金额为 0：不落行、不占名额、金额恒 0 */
    public static RebateClaimResultVO noRebate(String refId) {
        return new RebateClaimResultVO(RebateClaimApiState.NO_REBATE, null, null, refId, 0, 0);
    }

    /** 同步领取异常，领取任务留 PENDING 等待重放：不向用户承诺金额，金额恒 0 */
    public static RebateClaimResultVO pending(String refId) {
        return new RebateClaimResultVO(RebateClaimApiState.PENDING, null, null, refId, 0, 0);
    }
}
