package com.betterschool.domain.chat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话成员名册（room_member，会话维度「这个会话有谁」的正式查询面，1v1 恒两行）：
 * 对家回查/成员扇出从名册走；per-user 状态归 {@link UserRoomEntity}
 */
@Data
public class RoomMemberEntity {

    private Long id;
    private String roomId;
    private String userId;

    /** 入群水位：1v1 建会话时为 0；群聊上线即「新成员从哪条 seq 起收消息」 */
    private long joinSeq;

    private LocalDateTime createdAt;
}
