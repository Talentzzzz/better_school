package com.betterschool.marketing.domain.rebate.adapter.repository;

import com.betterschool.marketing.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.marketing.domain.rebate.model.valobj.RebateTypeVO;

import java.util.List;

/**
 * 行为返利仓储（action_rebate_config 的唯一读写入口）：按 (action_type, rebate_type) 取开启规则值、
 * 后台列表与 upsert。
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
}
