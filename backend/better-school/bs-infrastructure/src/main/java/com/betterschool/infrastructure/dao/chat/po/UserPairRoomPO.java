package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户对 → 1v1 会话映射（user_pair_room）：同一对人恒一行，uk_pair 是建会话的幂等裁决者；
 * room 表不再承载配对（user_min/user_max/uk_direct_pair 已迁出），见 sql.md
 */
@Data
public class UserPairRoomPO {

    private Long id;

    private String userMin;

    private String userMax;

    private String roomId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
