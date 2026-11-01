package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项（服务端拼装展示字段）：拼对方 userId+昵称+头像；
 * unread = last_seq_copy − 水位线 O(1)（用户维度成员行上就地可算）
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

    /** 会话来源 ERRAND/PRODUCT（由市集「私信联系」入口首建时非空） */
    private String source;

    private long unread;

    private String lastMsgType;

    private String lastMsgPreview;

    private LocalDateTime lastMsgAt;
}
