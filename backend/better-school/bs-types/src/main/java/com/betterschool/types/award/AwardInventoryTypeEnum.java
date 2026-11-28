package com.betterschool.types.award;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 奖品库存账户的类型：台账 {@code award_inventory.type} 列的取值，回答「这个账户走哪段流转」。
 * <p>
 * 类型由运营在投放 / 维护库存时直接给定（原先由来源配额池段决定，配额池已删除）；
 * 抽奖预扣/确认只打这一张台账，按 {@code type} 分支两条 UPDATE。
 * <p>
 * {@code code} 是唯一的对外表达：日志、消息载荷都用它，枚举名只活在代码里。
 */
@Getter
@AllArgsConstructor
public enum AwardInventoryTypeEnum {

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
    public static AwardInventoryTypeEnum fromCode(String code) {
        for (AwardInventoryTypeEnum awardInventoryType : values()) {
            if (awardInventoryType.code.equals(code)) {
                return awardInventoryType;
            }
        }
        return null;
    }
}
