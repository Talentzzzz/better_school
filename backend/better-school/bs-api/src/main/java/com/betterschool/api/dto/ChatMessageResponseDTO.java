package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息体：与 WS MSG 推送载荷字段对齐（spec §3），发送 200 转正与补拉/历史共用
 */
@Data
public class ChatMessageResponseDTO {

    private String roomId;

    private String messageId;

    /** 消息序号（会话内单调递增） */
    private Long seq;

    private String fromUserId;

    private String fromNickname;

    private String fromAvatar;

    private String msgType;

    /** NORMAL/RECALLED（RECALLED 时 content/ref 为空壳，前端渲染「已撤回」灰字） */
    private String status;

    private String content;

    private String refType;

    private String refId;

    private String clientMsgId;

    /** per-user 投递序号（仅补拉返回，增量补拉游标；发送/历史为 NULL） */
    private Long deliverySeq;

    private LocalDateTime createdAt;
}
