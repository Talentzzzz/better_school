package com.betterschool.domain.rebate.adapter.repository;

import com.betterschool.domain.rebate.model.entity.RebateClaimEntity;
import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.model.valobj.RebateTypeVO;

import java.util.List;

/**
 * 行为返利仓储：返利规则（action_rebate_config）按 (action_type, rebate_type) 取开启规则值、后台列表与
 * upsert；行为返利领取记录（action_rebate_claim）原子领取、按 ref / claimId 查询与置 GRANTED。
 * <p>
 * 表里只有「某行为发生 → 发多少积分 / 多少次」这一件事；**有效期不在这里**——积分有效期归
 * points 域（points_validity_config）、次数有效期归 raffle 域（draw_count_config，抽奖次数与抽奖同域），
 * 规则的 {@code config_id} 只是指向那一份配置的 id。
 * <p>
 * 两个桶域入账时经各自的只读 port（{@code IPointsRebatePort} / {@code IDrawCountRebatePort}）读本仓储，
 * 判据（要不要发、查哪种返利类型）留在消费方域。
 */
public interface IRebateRepository {

    /** 某行为某返利类型的开启规则值（state=open）：未配置或已关闭返回 null */
    Integer findOpenRebateValue(String actionType, RebateTypeVO rebateType);

    /** 全部返利规则（后台列表，create_time ASC） */
    List<RebateConfigEntity> listRebates();

    /** 按 uk (action_type, rebate_type) upsert 一条返利规则，返回 upsert 后的行 */
    RebateConfigEntity upsertRebate(RebateConfigEntity rebate);

    /**
     * 原子领取：查重 + 插 claim +（CLAIMED 时）插 credit outbox，仓储内一个 REQUIRED 本地事务；
     * 提交后同步发送 credit 消息。
     * <p>
     * 同一 {@code (userId, actionType, refId)} 重放返回已有快照（不重复插 outbox、不发送）；
     * 同 {@code (userId, actionType, periodKey)} 已被其他 ref 领取时返回 DENIED，不落 claim 行。
     */
    RebateClaimResultVO claim(RebateClaimEntity claimEntity);

    /** 按 (userId, actionType, refId) 查领取记录；无则 null（普通读，不加锁） */
    RebateClaimResultVO findClaimByRef(String userId, String actionType, String refId);

    /** 按 claimId 查领取记录；无则 null（客服排障用，普通读，不加锁） */
    RebateClaimResultVO findClaimByClaimId(String claimId);

    /** CLAIMED → GRANTED；条件 UPDATE，幂等（重复调用无副作用） */
    void markGranted(String claimId);
}
