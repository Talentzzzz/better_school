package com.betterschool.domain.wallet.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 钱包流水类型（{@code wallet_detail.type}），按 {@link #name()} 落库、按 {@code valueOf} 读回，
 * 不存 ordinal——所以枚举内的声明顺序不承载任何语义，历史行也不会因为新增类型而读错。
 * <p>
 * 商品订单链路已从「冻结 / 解冻」翻成平台代收的双边语义：买家支付落 {@link #PRODUCT_PAY}、
 * 买家退款落 {@link #PRODUCT_REFUND}、卖家结算落 {@link #PRODUCT_INCOME}，平台侧每一笔都配一条
 * {@link #PLATFORM_IN} / {@link #PLATFORM_OUT}。同一笔货款必然成对出现两行（业务方一行 + 平台一行），
 * 只有单边行意味着事务被拆开或中途宕机——这正是资金孤儿扫描的判据。
 * <p>
 * 声明顺序按「现行类型在前（非商品链路 → 商品链路 → 平台侧）、只剩历史读取用途的废弃类型垫底」排，
 * 让读代码的人不会把已不再写入的类型误当成现行类型。
 */
@Getter
@AllArgsConstructor
public enum WalletDetailTypeEnum {

    // 非商品链路的资金动作，语义原样保留：充值链路仍写 RECHARGE；冻结 / 解冻 / 收入若还有流程调用，行为不变
    RECHARGE("充值"),
    FROZEN("托管冻结"),
    UNFROZEN("解冻退回"),
    INCOME("接单收入"),

    // 商品链路：买家出账 → 卖家入账，平台侧配对入账 / 出账
    PRODUCT_PAY("商品支付出账"),
    PRODUCT_REFUND("商品退款入账"),
    PRODUCT_INCOME("商品售卖收入"),
    PLATFORM_IN("平台代收入账"),
    /**
     * 平台出账：确认收货时结算给卖家、取消 / 支付超时退回买家，两个方向共用本类型。
     * 方向不写进类型，由订单状态与同订单的配对行（{@code PRODUCT_INCOME} / {@code PRODUCT_REFUND}）判定。
     */
    PLATFORM_OUT("平台出账"),

    /**
     * 商品托管冻结（历史类型）。
     *
     * @deprecated 商品链路已改为 {@link #PRODUCT_PAY} 出账，不再写入本类型；保留仅为读取未迁移的历史流水行
     *         （{@code WalletDetailTypeEnum.valueOf} 反序列化），删掉会让历史库读流水直接抛
     *         {@link IllegalArgumentException}。历史行的一次性映射见
     *         {@code docs/adr/0019-platform-escrow-account.md}（{@code PRODUCT_FROZEN → PRODUCT_PAY}）。
     */
    @Deprecated
    PRODUCT_FROZEN("商品托管冻结"),
    /**
     * 商品托管解冻（历史类型）。
     *
     * @deprecated 商品链路已改为 {@link #PRODUCT_REFUND} 退款入账，不再写入本类型；保留仅为读取未迁移的历史流水行
     *         （{@code WalletDetailTypeEnum.valueOf} 反序列化），删掉会让历史库读流水直接抛
     *         {@link IllegalArgumentException}。历史行的一次性映射见
     *         {@code docs/adr/0019-platform-escrow-account.md}（{@code PRODUCT_UNFROZEN → PRODUCT_REFUND}）。
     */
    @Deprecated
    PRODUCT_UNFROZEN("商品托管解冻");

    private final String desc;
}
