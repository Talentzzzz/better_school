package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 商品状态机：
 * 提交：发布即 ON_SALE（对外可见）
 * 草稿箱：DRAFT 仅卖家可见，可编辑、重新提交上架；OFF_SHELF 已废弃（存量迁移为 DRAFT）；
 * 上架后主动下架 / 到期（满 30 天扫描）同样汇入 DRAFT，可重新提交上架
 * 交易（multi-quantity-listing）：ON_SALE →(下单扣到 0 件)→ SOLD；取消/超时回补 remaining > 0 时翻回
 * ON_SALE；SOLD 重上架受库存闸门约束。{@link #LOCKED} 为历史遗留值（旧"交易中"，订单流已停写，
 * 仅存量行可见，保留枚举防反序列化炸裂），不要再写入
 */
@Getter
@AllArgsConstructor
public enum ProductStatusEnum {

    DRAFT("草稿"),
    ON_SALE("在售"),
    /** 历史遗留值（multi-quantity-listing 前的"交易中"）：订单流已停写，仅存量行可见 */
    LOCKED("交易中"),
    SOLD("已出"),
    DELETED("已删除");

    private final String desc;
}
