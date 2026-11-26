package com.betterschool.domain.rebate.service;

import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;

import java.util.List;

/**
 * 行为返利服务（返利域）：只读规则给两个桶域入账用，写只来自后台管理面
 * （HTTP ADMIN，见 trigger 的 MarketingConfigController）。
 * <p>
 * 有效期配置不在本域——积分 / 次数有效期分别归 points 与 raffle 域（抽奖次数与抽奖同域）。
 */
public interface IRebateService {

    /** 全部返利规则 */
    List<RebateConfigEntity> listRebates();

    /** 按 (actionType, rebateType) upsert 一条返利规则，返回 upsert 后的行；入参不合法抛 CONFIG_PARAM_ERROR */
    RebateConfigEntity upsertRebate(RebateConfigEntity rebate);

    /**
     * 行为返利同步领取。顺序固定：
     * <ol>
     *   <li>先按 {@code (userId, actionType, refId)} 查已有领取：命中即幂等返回已有快照
     *       （不读当前规则、不插任何行）；</li>
     *   <li>未命中才读 action_rebate_config 两条 open 规则（POINTS / DRAW_COUNT）构快照；两者都为 0 或
     *       都未开放 → NO_REBATE（不插 claim 行、不占名额）；否则调仓储 claim 一次。</li>
     * </ol>
     * 「先查已有」保证领取后规则被运营关闭时，同一 ref 的重放仍返回原来的 CLAIMED 快照而不是 NO_REBATE。
     *
     * @param periodKey 限次周期键（yyyyMMdd，Asia/Shanghai，由发帖事务定死）
     */
    RebateClaimResultVO claimActionRebate(String actionType, String userId, String refId, String periodKey);
}
