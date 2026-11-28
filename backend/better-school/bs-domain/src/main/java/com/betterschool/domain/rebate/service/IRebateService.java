package com.betterschool.domain.rebate.service;

import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;

import java.util.List;

/**
 * 行为返利服务（返利域）：规则读给领取快照与两个桶域旧行为入账用；规则写只来自后台管理面
 * （HTTP ADMIN，见 trigger 的 MarketingConfigController），领取记录只由本域 {@link #claimActionRebate}
 * 写入（仓储内一个本地事务）。
 * <p>
 * 有效期配置不在本域——积分 / 次数有效期分别归 points 与 draw 域（抽奖次数与抽奖同域）。
 */
public interface IRebateService {

    /** 全部返利规则 */
    List<RebateConfigEntity> listRebates();

    /** 按 (actionType, rebateType) upsert 一条返利规则，返回 upsert 后的行；入参不合法抛 CONFIG_PARAM_ERROR */
    RebateConfigEntity upsertRebateConfig(RebateConfigEntity rebateConfigEntity);

    /**
     * 行为返利同步领取。顺序固定：
     * <ol>
     *   <li>先按 {@code (userId, actionType, refId)} 查已有领取：命中即幂等返回已有快照
     *       （不读当前规则、不插任何行）；</li>
     *   <li>未命中才读 action_rebate_config 两条 open 规则（POINTS / DRAW_COUNT）构快照；两者都为 0 或
     *       都未开放 → NO_REBATE（不插订单行、不占名额）；否则按返利类型各建一行订单，调仓储 claim 一次。</li>
     * </ol>
     * 「先查已有」保证领取后规则被运营关闭时，同一 ref 的重放仍返回原来的 CLAIMED 快照而不是 NO_REBATE。
     *
     * @param refType   行为对象类型（{@code RebateRefTypeEnum#getCode()}，发帖 = post）
     * @param periodKey 限次周期键（yyyyMMdd，Asia/Shanghai，由发帖事务定死）
     */
    RebateClaimResultVO claimActionRebate(String actionType, String userId, String refType,
                                          String refId, String periodKey);

    /**
     * 请求线程用的同步领取：语义同 {@link #claimActionRebate}，但领取异常不抛——吞成 {@code CLAIM_ERROR}
     * 返回。用于「行为已提交、领取只是尽力而为」的场景（如发帖）：领取失败不能连累已提交的行为，
     * 行为侧的领取任务保持未发送，由 outboxScan 重放，重放按 ref 幂等，不会重复发。
     * <p>
     * 返回 {@code CLAIM_ERROR} 说明本次领取结果未知，调用方必须保留自己的重放任务，不能当作已处理。
     */
    RebateClaimResultVO claimActionRebateOrPending(String actionType, String userId, String refType,
                                                   String refId, String periodKey);

    /** 入账完成：CLAIMED → GRANTED（条件 UPDATE，幂等，重复调用无副作用） */
    void markGranted(String orderId);
}
