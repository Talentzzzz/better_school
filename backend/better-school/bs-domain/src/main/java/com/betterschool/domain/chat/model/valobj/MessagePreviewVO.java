package com.betterschool.domain.chat.model.valobj;

import lombok.Data;

/**
 * 会话列表预览的域内投影（预览渲染的唯一输入）：只有渲染规则要用到的四项。
 * 撤回态在仓储出库时由 state 收敛成布尔，渲染器不碰消息状态机。
 */
@Data
public class MessagePreviewVO {

    private MsgTypeVO msgType;

    private String content;

    /** CARD 引用类型（PRODUCT/POST；非 CARD 为 null） */
    private CardRefTypeVO refType;

    /** 是否已撤回：为真时 content/refType 一律不参与渲染，文案由渲染器给「[已撤回]」 */
    private boolean recalled;
}
