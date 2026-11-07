package com.betterschool.domain.chat.model.valobj;

/**
 * 会话来源（room.source）：1v1 首次由「私信联系」入口建会话时写入的市集域。
 * 建会话入口两处：跑腿单详情「联系」/ 商品详情「联系卖家」；
 * 首页与用户主页的私信入口**不带来源**（用户规则「从市集里私信走市集、从首页进去走底部」）。
 * 写入后不复写（首来源钉死）：同一对人先聊闲置又聊跑腿，仍是同一条会话、同一个标签
 */
public enum RoomSourceEnum {

    /** 跑腿单详情入口 */
    ERRAND,

    /** 商品（闲置）详情入口 */
    PRODUCT
}
