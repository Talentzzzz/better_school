package com.betterschool.domain.chat.model.valobj;

/**
 * 消息类型四类定死（撤回是消息状态不是类型）；
 * content 载荷语义按类型分：TEXT=裸文本≤500 / IMAGE=JSON{url,width,height} / CARD=恒空
 * / SYSTEM=JSON{notificationId,title,content,refType?,refId?}（仅系统助手账号可发，用户不可回复）
 */
public enum MsgTypeEnum {

    TEXT, IMAGE, CARD, SYSTEM
}
