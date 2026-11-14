package com.betterschool.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 会话列表分页包装（last_msg_at 倒序）
 */
@Data
public class ChatRoomListResponseDTO {

    private int page;

    private int pageSize;

    /** -1 = 未做精确 COUNT（与 feed 索引路径同口径）：会话列表目前不翻页，前端不消费该值 */
    private long total;

    private List<ChatRoomListItemResponseDTO> list;
}
