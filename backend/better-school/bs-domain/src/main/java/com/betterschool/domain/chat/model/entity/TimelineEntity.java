package com.betterschool.domain.chat.model.entity;

import lombok.Data;

/**
 * 时间线（不可变瘦收件箱，只插不改）：一条消息 N 个成员 N 行；
 * delivery_seq 为 per-user 投递序号（与消息序号是两个序号空间不可混用），即增量补拉游标。
 */
@Data
public class TimelineEntity {

    private String userId;
    private String roomId;

    /** 消息序号副本（room 内） */
    private Long seq;

    private String msgId;

    /** per-user 投递序号：user_seq 计数器扇出事务内取号，per-user 单调且=提交序 */
    private Long deliverySeq;
}
