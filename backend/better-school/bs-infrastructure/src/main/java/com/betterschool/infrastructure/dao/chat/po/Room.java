package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话行（room）：id 是物理自增，room_id 是业务 ID（对外标识，也是 room_member/message/timeline 的引用键）
 */
@Data
public class Room {

    private Long id;
    private String roomId;
    private String source;
    private String creatorId;
    private Long lastSeq;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
