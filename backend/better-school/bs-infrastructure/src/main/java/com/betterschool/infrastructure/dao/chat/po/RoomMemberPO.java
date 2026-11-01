package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话成员状态行（room_member，每会话恒两行）：水位线、隐藏位与会话列表副本（排序键 + 预览三件套）
 * 都在这张表——用户维度会话列表唯一查询面
 */
@Data
public class RoomMemberPO {

    private Long id;
    private String roomId;
    private String userId;
    /** 会话水位副本：未读数 = 此列 − lastReadSeq，用户维度就地可算 */
    private Long lastSeqCopy;
    private String lastMsgType;
    private String lastMsgPreview;
    private LocalDateTime lastMsgAt;
    private Long lastReadSeq;
    private LocalDateTime hiddenAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
