package com.betterschool.domain.chat.model.valobj;

/**
 * 对家角色（会话列表按来源现算，不落库）：会话行上那枚角色小标签的取值。
 * 推导见 {@code ChatQueryServiceImpl#peerRoleOf}——闲置按「本人是不是首建发起人」分买卖两面，
 * 跑腿单两侧都能发起、来源列定不了方向，按票面口径对家恒为跑手
 */
public enum RoomPeerRoleVO {

    /** 跑手（跑腿单的接单方） */
    TAKER,

    /** 卖家（闲置「我买的」侧对家） */
    SELLER,

    /** 买家（闲置「我卖的」侧对家） */
    BUYER
}
