package com.betterschool.marketing.types.inventory;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 奖品库存层的类型（award-inventory 两层模型）：统一台账 {@code draw_strategy_award_inventory.type}
 * 列的取值，回答「这个奖品库存账户走哪段流转」。
 * <p>
 * 与 {@link InventorySegmentVO} 的分工：段（SEGMENT）是**配额池**的选边参数（实物营销配额 /
 * 虚拟库存配额，只被「分配」转移）；类型（TYPE）是**奖品库存账户**的一列（抽奖预扣/确认只打统一台账，
 * 按 {@code type} 分支两条 UPDATE）。一个账户的类型由它的来源池段唯一决定：实物池 → {@link #PHYSICAL}，
 * 虚拟池 → {@link #VIRTUAL}。
 * <p>
 * {@code code} 是唯一的对外表达：日志、消息载荷都用它，枚举名只活在代码里（口径同
 * {@link InventorySegmentVO}）。
 */
@Getter
@AllArgsConstructor
public enum AwardInventoryType {

    /** 实物奖品库存：surplus → waiting → issued 两段式（合并进 waiting，兑换确认转 issued） */
    PHYSICAL("physical"),

    /** 虚拟奖品库存：surplus → issued（没有兑换确认，waiting 恒 0） */
    VIRTUAL("virtual");

    private final String code;

    /**
     * 按 {@link #getCode()} 反查类型。
     *
     * @return 未知 code 返回 null，由调用方决定是报参数错误还是丢弃消息
     */
    public static AwardInventoryType fromCode(String code) {
        for (AwardInventoryType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
