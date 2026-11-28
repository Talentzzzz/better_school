package com.betterschool.domain.rebate.model.valobj;

import com.betterschool.types.rebate.RebateClaimStateEnum;
import lombok.Getter;

import java.util.List;

/**
 * 一次行为返利领取的结果：状态 + 行为 ID + 按返利类型汇总的项列表。
 * <p>
 * 项来自订单行（一个返利类型一项，最多 POINTS / DRAW_COUNT 两项）；{@code DENIED} / {@code NO_REBATE} /
 * {@code CLAIM_ERROR} 没有落任何订单行，项列表恒为空——这三种状态没有承诺过任何金额。
 * <p>
 * 对外响应取 {@link RebateClaimStateEnum#apiState()}：{@code GRANTED} 折叠成 {@code CLAIMED}，
 * 所以同一次行为重放时看到的状态与首次领取一致。
 */
@Getter
public class RebateClaimResultVO {

    private final RebateClaimStateEnum rebateClaimState;
    /** 本次行为 ID（发帖 = postId），用于响应拼装 */
    private final String refId;
    /** 按返利类型汇总的领取项（一个类型一项） */
    private final List<RebateClaimItemVO> rebateClaimItemVOList;

    private RebateClaimResultVO(RebateClaimStateEnum rebateClaimState, String refId,
                                List<RebateClaimItemVO> rebateClaimItemVOList) {
        this.rebateClaimState = rebateClaimState;
        this.refId = refId;
        this.rebateClaimItemVOList = List.copyOf(rebateClaimItemVOList);
    }

    /** 本次行为抢到名额：订单行已落库，状态 CLAIMED，项金额为领取快照 */
    public static RebateClaimResultVO claimed(String refId, List<RebateClaimItemVO> rebateClaimItemVOList) {
        return new RebateClaimResultVO(RebateClaimStateEnum.CLAIMED, refId, rebateClaimItemVOList);
    }

    /** 幂等重放命中已全部入账的订单行：落库状态 GRANTED（对外经 apiState() 仍报 CLAIMED） */
    public static RebateClaimResultVO granted(String refId, List<RebateClaimItemVO> rebateClaimItemVOList) {
        return new RebateClaimResultVO(RebateClaimStateEnum.GRANTED, refId, rebateClaimItemVOList);
    }

    /** 当天名额已被该用户的其他行为领取（日限命中）：不落行、项列表为空 */
    public static RebateClaimResultVO denied(String refId) {
        return new RebateClaimResultVO(RebateClaimStateEnum.DENIED, refId, List.of());
    }

    /** 无开放规则或快照金额为 0：不落行、不占名额、项列表为空 */
    public static RebateClaimResultVO noRebate(String refId) {
        return new RebateClaimResultVO(RebateClaimStateEnum.NO_REBATE, refId, List.of());
    }

    /** 同步领取抛异常、结果未知：领取任务留待重放，不向用户承诺金额，项列表为空 */
    public static RebateClaimResultVO claimError(String refId) {
        return new RebateClaimResultVO(RebateClaimStateEnum.CLAIM_ERROR, refId, List.of());
    }
}
