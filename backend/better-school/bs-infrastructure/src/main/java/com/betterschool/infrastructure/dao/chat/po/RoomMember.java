package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话成员名册行（room_member，会话维度「这个会话有谁」的正式查询面，1v1 恒两行）：
 * 对家回查/成员扇出都从名册走；分库片键语义贴 room_id（uk_room_user 首列）。
 * join_seq 是入群水位：1v1 建会话时为 0，群聊上线即「新成员从哪条 seq 起收消息」
 */
@Data
public class RoomMember {

    private Long id;
    private String roomId;
    private String userId;
    private Long joinSeq;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
