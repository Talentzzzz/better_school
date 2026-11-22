package com.betterschool.domain.rebate.service;

import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;

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
}
