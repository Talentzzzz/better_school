package com.betterschool.domain.chat.model.entity;

import com.betterschool.domain.chat.model.valobj.CardRefTypeEnum;
import com.betterschool.domain.chat.model.valobj.MsgStatusEnum;
import com.betterschool.domain.chat.model.valobj.MsgTypeEnum;
import com.betterschool.domain.chat.model.valobj.RecallByEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息（内容中心，只写一份）：内容只写一份，撤回/强制撤回只改这里。
 */
@Data
public class MessageEntity {

    private String messageId;
    private String roomId;

    /** 消息序号（room.last_seq 取号；会话内从 1 起，跨会话不可比） */
    private Long seq;

    /** 发送者用户 ID（SYSTEM 系统消息为 NULL） */
    private String fromUserId;

    private MsgTypeEnum msgType;

    /** 载荷（语义按类型分，见 MsgTypeEnum） */
    private String content;

    /** 客户端幂等键（UUID，乐观上屏重发复用同键；SYSTEM 为 NULL 不受约束） */
    private String clientMsgId;

    /** CARD 引用（仅 CARD 非空） */
    private CardRefTypeEnum refType;
    /** 卡片引用对象业务 ID（POST/PRODUCT 均为 UUID 字符串） */
    private String refId;

    private MsgStatusEnum status;
    private LocalDateTime recalledAt;
    private RecallByEnum recalledBy;

    private LocalDateTime createdAt;

    public boolean isRecalled() {
        return status == MsgStatusEnum.RECALLED;
    }
}
