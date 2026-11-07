package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

/**
 * 会话列表的对家成员行（room_member 单表回查，每会话恰一行对家）：
 * 原 selectRoomItems 自连 JOIN 的多查形态，仓储按 room_id 索引拼装进 RoomListItemPO.peerUserId。
 */
@Data
public class RoomPeerRowPO {

    private String roomId;
    private String userId;
}
