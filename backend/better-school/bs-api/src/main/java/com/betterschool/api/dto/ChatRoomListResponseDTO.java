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

    private long total;

    private List<ChatRoomListItemResponseDTO> list;
}
