package com.betterschool.domain.chat.model.valobj;

import com.betterschool.domain.chat.model.entity.MessageEntity;
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
    private RoomSourceEnum source;

    /** 会话创建人用户 ID（peerRole 的推导锚点） */
    private String creatorId;

    /** 未读数 = 本人在会话的水位副本 − 已读水位线（用户维度行上就地可算） */
    private long unread;

    /** 预览真源中间态（票 02 预览去副本）：按 (roomId, room.last_seq) 点查 message 最新一条，
     * 由域服务渲染成 lastMsgType/lastMsgPreview 后即完成使命，不对外下发 */
    private MessageEntity lastMessage;

    /** 最后一条消息类型（读时按 message 真相现算，不落副本） */
    private MsgTypeVO lastMsgType;

    /** 预览文案（读时按 message 真相渲染：RECALLED→[已撤回]、IMAGE→[图片]、CARD→类型标签、TEXT→截 200） */
    private String lastMsgPreview;

    private LocalDateTime lastMsgTime;
}
