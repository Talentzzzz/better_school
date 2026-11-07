package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表查询投影：四段查拼装——本人 user_room 行分页直查（roomId + 排序键 + readSeq），
 * room 真相（source/initiatorId/lastSeq，lastSeq 即未读数真源）、对家 userId（peerUserId，名册回查）
 * 与预览真源（按 (roomId, lastSeq) 点查 message 最新一条）由仓储按 room_id 集回查填充；
 * unread = lastSeq − readSeq 由调用方拼装
 */
@Data
public class RoomListItemPO {

    private Long id;

    private String roomId;

    /** 对家 user_id（1v1 恒两行名册，名册表回查直出） */
    private String peerUserId;

    private String source;

    private String initiatorId;

    /** 会话水位真源（room.last_seq，room 行批量回查带出）：unread = 此列 − readSeq，亦是预览点查锚点 */
    private Long lastSeq;

    private LocalDateTime lastMsgTime;

    private Long readSeq;
}
