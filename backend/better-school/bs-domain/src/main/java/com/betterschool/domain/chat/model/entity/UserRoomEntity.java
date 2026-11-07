package com.betterschool.domain.chat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户会话状态（user_room，唯一会被更新的用户维度行）：水位线/隐藏/列表预览都在这一行；未读数不落库，
 * 由 room.last_seq − readSeq 现算。「这个会话有谁」的会话维度事实归名册 {@link RoomMemberEntity}
 */
@Data
public class UserRoomEntity {

    private Long id;
    private String roomId;
    private String userId;

    /** 已读水位线：未读数 = room.last_seq − 此列；已读回执 = 对方的此列；打开会话推到 room.last_seq */
    private long readSeq;

    /** 会话隐藏时间（用户侧删除会话，软删）；新消息清空弹回 */
    private LocalDateTime hiddenAt;

    private LocalDateTime createdAt;
}
