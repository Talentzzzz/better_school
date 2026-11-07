package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户会话状态行（user_room，每用户每会话一行）：水位线、隐藏位与会话列表副本（排序键 + 预览三件套）
 * 都在这张表——用户维度会话列表唯一查询面；未读数不落库，由 room.last_seq − readSeq 现算。
 * 分库片键语义贴 user_id（uk_user_room 首列前置）
 */
@Data
public class UserRoomPO {

    private Long id;
    private String roomId;
    private String userId;
    private String lastMsgType;
    private String lastMsgPreview;
    private LocalDateTime lastMsgTime;
    private Long readSeq;
    private LocalDateTime hiddenAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
