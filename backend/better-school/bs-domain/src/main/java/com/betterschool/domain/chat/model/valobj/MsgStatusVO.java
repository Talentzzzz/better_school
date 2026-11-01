package com.betterschool.domain.chat.model.valobj;

/**
 * 消息状态：RECALLED 撤回占 seq 不回退、不动水位线、计入未读；content 库内保留取证，
 * 读路径与下发一律脱敏为「已撤回」空壳（票 06）
 */
public enum MsgStatusVO {

    NORMAL, RECALLED
}
