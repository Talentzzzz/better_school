package com.betterschool.domain.chat.model.valobj;

/**
 * 撤回者：USER=发送者 2 分钟窗口内自行撤回 / AUDITOR=举报强制撤回（无窗口限制，票 06）
 */
public enum RecallByVO {

    USER, AUDITOR
}
