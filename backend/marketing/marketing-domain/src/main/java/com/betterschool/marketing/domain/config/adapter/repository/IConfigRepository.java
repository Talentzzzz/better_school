package com.betterschool.marketing.domain.config.adapter.repository;

import com.betterschool.marketing.domain.config.model.entity.RebateConfigEntity;
import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;

import java.util.List;

/**
 * 营销配置仓储：积分/次数有效期（两张单行配置）+ 行为返利规则（action_rebate_config）。
 * 三张表的**唯一读写入口**——两个桶域（points / drawcount）入账算 expire_at、查返利规则时
 * 只读注入本接口（CLAUDE.md §1 的只读跨域例外），后台管理面的写也全部走这里。
 */
public interface IConfigRepository {

    /** 积分有效期天数（points_validity_config 单行，种子 id=1 / 365 天）；缺行抛系统异常 */
    int pointsValidDays();

    /** 更新积分有效期天数（单行，id=1） */
    void updatePointsValidDays(int days);

    /** 抽奖次数有效期天数（draw_count_config 单行，种子 id=1 / 30 天）；缺行抛系统异常 */
    int drawCountValidDays();

    /** 更新抽奖次数有效期天数（单行，id=1） */
    void updateDrawCountValidDays(int days);

    /** 某行为某返利类型的开启规则值（state=open）：未配置或已关闭返回 null */
    Integer findOpenRebateValue(String actionType, RebateTypeVO rebateType);

    /** 全部返利规则（后台列表，create_time ASC） */
    List<RebateConfigEntity> listRebates();

    /** 按 uk (action_type, rebate_type) upsert 一条返利规则，返回 upsert 后的行 */
    RebateConfigEntity upsertRebate(RebateConfigEntity rebate);
}
