package com.betterschool.domain.chat.model.valobj;

/**
 * CARD 消息引用类型（结构化外键非内容：跳转语义显式、可索引反查，票 06）：
 * 渲染时实时查对象，失效显示「内容已失效」
 */
public enum CardRefTypeVO {

    PRODUCT, POST, ERRAND
}
