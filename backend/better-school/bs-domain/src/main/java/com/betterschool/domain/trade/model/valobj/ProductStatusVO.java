package com.betterschool.domain.trade.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 商品状态机：
 * 提交：发布即 ON_SALE（对外可见）
 * 草稿箱：DRAFT 仅卖家可见，可编辑、重新提交上架；OFF_SHELF 已废弃（存量迁移为 DRAFT）；
 * 上架后主动下架 / 到期（满 30 天扫描）同样汇入 DRAFT，可重新提交上架
 * 交易：ON_SALE →(下单)→ LOCKED →(确认)→ SOLD；SOLD 可重新上架回 ON_SALE
 */
@Getter
@AllArgsConstructor
public enum ProductStatusVO {

    DRAFT("草稿"),
    ON_SALE("在售"),
    LOCKED("交易中"),
    SOLD("已出"),
    DELETED("已删除");

    private final String desc;
}
