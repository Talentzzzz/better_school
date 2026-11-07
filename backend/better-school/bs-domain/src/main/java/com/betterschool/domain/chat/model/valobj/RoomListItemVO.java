package com.betterschool.domain.chat.model.valobj;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项（服务端拼装展示字段）：unread = room.last_seq − read_seq（服务端拼装，
 * 都在用户维度 user_room 行上就地可算）；服务端拼对方 userId+昵称+头像（对家 id 由名册回查）。
 * source / peerRole 供市集消息页按域筛与出角色标签（peerRole 按请求者现算，不落库）
 */
@Data
public class RoomListItemVO {

    private String roomId;

    /** 对方用户 ID */
    private String peerUserId;

    private String peerNickname;

    private String peerAvatar;

    /** 对家角色（仅会话来源非空时非空）：会话行上的角色小标签取值 */
    private RoomPeerRoleVO peerRole;

    /** 会话来源（由市集入口首建时非空）：市集消息页按它分 tab */
    private RoomSourceVO source;

    /** 首建发起人用户 ID（peerRole 的推导锚点） */
    private String initiatorId;

    /** 未读数 = 本人在会话的水位副本 − 已读水位线（用户维度行上就地可算） */
    private long unread;

    private MsgTypeVO lastMsgType;

    private String lastMsgPreview;

    private LocalDateTime lastMsgTime;
}
