package com.betterschool.domain.chat.model.valobj;

/**
 * 对家角色（会话列表按来源现算，不落库）：会话行上那枚角色小标签的取值。
 * 推导见 {@code ChatQueryServiceImpl#peerRoleOf}——按「本人是不是会话创建人」分买卖两面
 */
public enum RoomPeerRoleVO {

    /** 卖家（闲置「我买的」侧对家） */
    SELLER,

    /** 买家（闲置「我卖的」侧对家） */
    BUYER
}
