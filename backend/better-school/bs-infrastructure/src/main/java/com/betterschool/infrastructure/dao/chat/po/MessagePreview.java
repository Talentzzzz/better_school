package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

/**
 * 会话列表预览投影行（票 02 预览去副本）：预览渲染只认「类型 + 文案 + 引用类型 + 撤回态」四项，
 * 不把整条 message 拉回来。会话锚点由 (room_id, last_seq) 给出，seq/msg_id 无需随行带出。
 */
@Data
public class MessagePreview {

    private String msgType;

    private String content;

    /** CARD 引用类型（PRODUCT/POST；非 CARD 为 null） */
    private String refType;

    /** NORMAL/RECALLED：撤回即空壳，撤回后 content/refType 不出服务端 */
    private String state;
}
