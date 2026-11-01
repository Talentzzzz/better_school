package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表查询投影（room_member 主行 JOIN room 补会话真相 + 自连出对家）：列表三件套与水位副本
 * 都在本人的成员行上，unread 由调用方 O(1) 拼装
 */
@Data
public class RoomListItemPO {

    private Long id;

    private String roomId;

    /** 对家 user_id（1v1 恒两行成员，SQL 自连直出） */
    private String peerUserId;

    private String source;

    private String initiatorId;

    private Long lastSeqCopy;

    private String lastMsgType;

    private String lastMsgPreview;

    private LocalDateTime lastMsgAt;

    private Long lastReadSeq;
}
