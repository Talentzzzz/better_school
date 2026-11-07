package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表查询投影：三段查拼装——本人成员行分页直查（roomId + 列表三件套 + lastReadSeq），
 * room 真相（source/initiatorId/lastSeq，lastSeq 即未读数真源）与对家 userId（peerUserId）由仓储
 * 按 room_id 集二次回查填充；unread = lastSeq − lastReadSeq 由调用方拼装
 */
@Data
public class RoomListItemPO {

    private Long id;

    private String roomId;

    /** 对家 user_id（1v1 恒两行成员，SQL 自连直出） */
    private String peerUserId;

    private String source;

    private String initiatorId;

    /** 会话水位真源（room.last_seq，随列表 JOIN room 投影带出）：unread = 此列 − lastReadSeq */
    private Long lastSeq;

    private String lastMsgType;

    private String lastMsgPreview;

    private LocalDateTime lastMsgAt;

    private Long lastReadSeq;
}
