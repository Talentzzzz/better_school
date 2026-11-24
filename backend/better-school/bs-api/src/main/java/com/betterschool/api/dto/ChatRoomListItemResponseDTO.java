package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项（服务端拼装展示字段）：拼对方 userId+昵称+头像；
 * unread = room.last_seq − 水位线（服务端拼装，不落副本）
 */
@Data
public class ChatRoomListItemResponseDTO {

    private String roomId;

    /** 对方用户 ID */
    private String peerUserId;

    private String peerNickname;

    private String peerAvatar;

    /** 对家角色 TAKER/SELLER/BUYER（仅 DIRECT 且会话来源非空时非空）：会话行角色小标签 */
    private String peerRole;

    /** 会话来源 PRODUCT（由市集「联系卖家」入口首建时非空） */
    private String source;

    private long unread;

    private String lastMsgType;

    private String lastMsgPreview;

    private LocalDateTime lastMsgAt;
}
