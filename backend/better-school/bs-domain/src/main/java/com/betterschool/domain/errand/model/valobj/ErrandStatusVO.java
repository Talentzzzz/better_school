package com.betterschool.domain.errand.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;

/**
 * 订单状态机（先付后审，ADR-0003；草稿/锁单扩展：抢 ≠ 开工）：
 * DRAFT →(提交/必填+同步初筛)→ PENDING_PAYMENT →(支付冻结)→ PENDING_REVIEW(已付款待审) →(审核通过)→ PUBLISHED
 *   →(抢单，抢≠开工)→ LOCKED →(接单方确认接单)→ CONFIRMED →(取货)→ PICKED_UP →(送达)→ DELIVERED →(发布方确认/24h自动)→ COMPLETED(终态)
 * 草稿：可编辑全字段、可软删（DELETED 终态，仅从 DRAFT 可达）、不付款、不对外；REJECTED 是资金终态，不复活回 DRAFT
 * 创建：同步初筛命中即创建失败（不落库），通过则 PENDING_PAYMENT
 * 审核：PENDING_REVIEW 仅作者可见；通过 → PUBLISHED，拒绝 → REJECTED(终态，解冻退款)；待审期作者可取消（解冻）
 * 取消：发布方限取货前（含锁单期）→ CANCELLED(终态)；接单方仅 CONFIRMED 可退 → 回 PUBLISHED；超时统一归 CANCELLED
 * 锁单：抢单（抢≠开工）写 taker_id + lock_seq+1 → LOCKED；持单人「确认接单」→ CONFIRMED（不改 lock_seq），
 *   「放弃」→ 回 PUBLISHED（清 taker_id，不扣分）；5min 未确认的自动释放由后续票落地
 * 仲裁：CONFIRMED/PICKED_UP/DELIVERED 任一方发起 → DISPUTED →(ADMIN 结案)→ CANCELLED(退发布方) / COMPLETED(划转)
 */
public enum ErrandStatusVO {

    DRAFT, PENDING_PAYMENT, PENDING_REVIEW, PUBLISHED, LOCKED, CONFIRMED, PICKED_UP, DELIVERED, COMPLETED,
    CANCELLED, DISPUTED, REJECTED, DELETED;

    public static ErrandStatusVO from(String name) {
        try {
            return ErrandStatusVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "订单状态不合法");
        }
    }
}
