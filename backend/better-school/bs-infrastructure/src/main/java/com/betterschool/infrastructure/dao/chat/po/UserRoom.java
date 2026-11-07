package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户会话状态行（user_room，每用户每会话一行）：水位线、隐藏位与列表排序键（last_msg_time）
 * 都在这张表——用户维度会话列表唯一查询面；预览不落副本（读时按 message 真相现算，票 02），
 * 未读数不落库，由 room.last_seq − readSeq 现算。分库片键语义贴 user_id（uk_user_room 首列前置）
 */
@Data
public class UserRoom {

    private Long id;
    private String roomId;
    private String userId;
    private LocalDateTime lastMsgTime;
    private Long readSeq;
    private LocalDateTime hiddenAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
