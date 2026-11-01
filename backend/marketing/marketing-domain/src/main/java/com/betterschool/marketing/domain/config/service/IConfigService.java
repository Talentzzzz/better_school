package com.betterschool.marketing.domain.config.service;

import com.betterschool.marketing.domain.config.model.entity.RebateConfigEntity;

import java.util.List;

/**
 * 营销配置服务（配置域）：积分/次数有效期 + 行为返利规则的读写。
 * 读给后台展示与两个桶域入账用，写只来自后台管理面（HTTP ADMIN，见 trigger 的 MarketingConfigController）。
 */
public interface IConfigService {

    /** 积分有效期天数（points_validity_config 单行） */
    int getPointsValidDays();

    /** 更新积分有效期天数 */
    void updatePointsValidDays(int days);

    /** 抽奖次数有效期天数（draw_count_config 单行） */
    int getDrawCountValidDays();

    /** 更新抽奖次数有效期天数 */
    void updateDrawCountValidDays(int days);

    /** 全部返利规则 */
    List<RebateConfigEntity> listRebates();

    /** 按 (actionType, rebateType) upsert 一条返利规则，返回 upsert 后的行；入参不合法抛 CONFIG_PARAM_ERROR */
    RebateConfigEntity upsertRebate(RebateConfigEntity rebate);
}
