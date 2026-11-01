package com.betterschool.domain.chat.model.entity;

import com.betterschool.domain.chat.model.valobj.RoomSourceVO;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话（恒 1v1，恰两名成员）：seq 取号 + 会话真相；列表预览/排序/未读冗余在用户维度 room_member 行上。
 */
@Data
public class RoomEntity {

    private String roomId;

    /** 会话来源：由市集「私信联系」入口首建时写入，写入后不复写（首来源钉死） */
    private RoomSourceVO source;

    /** 首建发起人用户 ID：会话来源的相对视角锚点 */
    private String initiatorId;

    /** 会话内消息序号水位：发消息事务内 UPDATE last_seq=last_seq+1 原子取号 */
    private long lastSeq;

    private LocalDateTime createdAt;
}
