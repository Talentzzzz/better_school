package com.betterschool.types.wallet;

/**
 * 钱包域自有常量：平台代收账户的保留 {@code user_id}。
 * 跨域 topic 等通用常量仍在 {@code types.common.Constants}（本类只收钱包域独占的保留值）。
 */
public class Constants {

    /**
     * 平台代收账户（买家先把钱付给平台，订单完成结算给卖家、取消/超时退回买家）：不是真实用户，
     * 而是 {@code virtual_wallet} 的一行——余额 + 流水 + 幂等键与普通用户账户完全同构，
     * 所以不需要新账户表、也不需要新迁移就能给平台记账。它不出现在任何用户可见接口里
     * （「钱包对用户隐藏」的产品口径不变）。
     * <p>
     * 与 {@link com.betterschool.types.common.Constants#SYSTEM_USER_ID}（通知助手）刻意取不同 ID：一个是消息通道的发送者，一个是资金的托管方；
     * 合成一个账号会让「系统消息」和「平台余额」两种角色互相纠缠，改一边就得担心另一边。
     */
    public final static String PLATFORM_USER_ID = "00000000-0000-0000-0000-000000000002";

}
