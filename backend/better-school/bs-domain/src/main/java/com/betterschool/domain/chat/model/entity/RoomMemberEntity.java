package com.betterschool.domain.chat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话成员（唯一会被更新的成员维度行）：水位副本/水位线/隐藏/列表预览都在这一行。
 */
@Data
public class RoomMemberEntity {

    private Long id;
    private String roomId;
    private String userId;

    /** 会话水位副本：未读数 = 此列 − lastReadSeq（用户维度就地可算）；写链路 GREATEST 只进不退 */
    private long lastSeqCopy;

    /** 已读水位线：未读数 = last_seq_copy − 此列；已读回执 = 对方的此列；打开会话推到 last_seq_copy */
    private long lastReadSeq;

    /** 会话隐藏时间（用户侧删除会话，软删）；新消息清空弹回 */
    private LocalDateTime hiddenAt;

    private LocalDateTime createdAt;
}
