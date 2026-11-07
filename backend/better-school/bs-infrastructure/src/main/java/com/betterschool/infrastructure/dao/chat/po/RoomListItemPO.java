package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表查询投影：三段查拼装——本人 user_room 行分页直查（roomId + 列表三件套 + readSeq），
 * room 真相（source/initiatorId/lastSeq，lastSeq 即未读数真源）与对家 userId（peerUserId，名册回查）由仓储
 * 按 room_id 集二次回查填充；unread = lastSeq − readSeq 由调用方拼装
 */
@Data
public class RoomListItemPO {

    private Long id;

    private String roomId;

    /** 对家 user_id（1v1 恒两行名册，名册表回查直出） */
    private String peerUserId;

    private String source;

    private String initiatorId;

    /** 会话水位真源（room.last_seq，room 行批量回查带出）：unread = 此列 − readSeq */
    private Long lastSeq;

    private String lastMsgType;

    private String lastMsgPreview;

    private LocalDateTime lastMsgTime;

    private Long readSeq;
}
