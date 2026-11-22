package com.betterschool.types.inventory;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 库存分段（spec §7.1）：两套 quota 表是**同一套模型的两个目标**，代码上用这一个参数选边，
 * 不再有「虚拟版方法 + 实物版方法」两份孪生。
 * <p>
 * {@code code} 是唯一的对外表达：合并消息载荷、日志都用它，枚举名只活在代码里。取值与表名对应：
 * {@link #QUOTA} = {@code physical_marketing_quota*}（实物营销配额），
 * {@link #VIRTUAL} = {@code virtual_inventory_quota*}（虚拟库存配额）。
 * <p>
 * 落在 {@code types} 而不是 domain：{@link Constants} 与本域的 wire 类型都要用它，
 * 而 {@code types} 不能反向依赖 domain（backend/CLAUDE.md §1）。
 */
@Getter
@AllArgsConstructor
public enum InventorySegmentVO {

    /** 实物营销配额：surplus → waiting → issued（合并进 waiting，兑换确认转 issued） */
    QUOTA("quota"),

    /** 虚拟库存配额：surplus → issued（没有兑换确认） */
    VIRTUAL("virtual");

    private final String code;

    /**
     * 按 {@link #getCode()} 反查段（job 参数、合并消息载荷解析共用）。
     *
     * @return 未知 code 返回 null，由调用方决定是报参数错误还是丢弃消息
     */
    public static InventorySegmentVO fromCode(String code) {
        for (InventorySegmentVO segment : values()) {
            if (segment.code.equals(code)) {
                return segment;
            }
        }
        return null;
    }
}
