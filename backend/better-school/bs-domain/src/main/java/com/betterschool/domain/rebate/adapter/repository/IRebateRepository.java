package com.betterschool.domain.rebate.adapter.repository;

import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.entity.RebateOrderEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.model.valobj.RebateTypeEnum;

import java.util.List;

/**
 * 行为返利仓储：返利规则（action_rebate_config）按 (action_type, rebate_type) 取开启规则值、后台列表与
 * upsert；行为返利订单（action_rebate_order）原子领取、按 ref / orderId 查询与置 GRANTED。
 * <p>
 * 表里只有「某行为发生 → 发多少积分 / 多少次」这一件事；**有效期不在这里**——积分有效期归
 * points 域（points_validity_config）、次数有效期归 draw 域（draw_count_config，抽奖次数与抽奖同域），
 * 规则的 {@code config_id} 只是指向那一份配置的 id。
 * <p>
 * 两个桶域不再直接读本仓储：返利规则由 case 层读取后把金额传给 points / draw 域入账，
 * 判据（要不要发、查哪种返利类型）留在编排层。
 */
public interface IRebateRepository {

    /** 某行为某返利类型的开启规则值（state=open）：未配置或已关闭返回 null */
    Integer findOpenRebateValue(String actionType, RebateTypeEnum rebateTypeVO);

    /** 全部返利规则（后台列表，create_time ASC） */
    List<RebateConfigEntity> listRebates();

    /** 按 uk (action_type, rebate_type) upsert 一条返利规则，返回 upsert 后的行 */
    RebateConfigEntity upsertRebateConfig(RebateConfigEntity rebateConfigEntity);

    /**
     * 原子领取：查重 + 插订单行（按返利类型拆行）+ 插 credit outbox，仓储内一个 REQUIRED 本地事务；
     * 提交后逐行同步发送 credit 消息。
     * <p>
     * 同一 {@code (userId, actionType, refType, refId)} 重放返回已有快照（不重复插 outbox、不发送）；
     * 同 {@code (userId, actionType, periodKey)} 已被其他 ref 领取时返回 DENIED，不落订单行。
     */
    RebateClaimResultVO claim(List<RebateOrderEntity> rebateOrderEntities);

    /** 按 (userId, actionType, refId) 查领取结果；无则 null（普通读，不加锁） */
    RebateClaimResultVO findClaimByRef(String userId, String actionType, String refId);

    /** 按 orderId 查订单行；无则 null（客服排障用，普通读，不加锁） */
    RebateOrderEntity findOrderByOrderId(String orderId);

    /** CLAIMED → GRANTED；条件 UPDATE，幂等（重复调用无副作用） */
    void markGranted(String orderId);
}
