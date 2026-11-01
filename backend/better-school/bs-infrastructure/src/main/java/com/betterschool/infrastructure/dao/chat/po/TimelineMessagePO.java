package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 补拉查询投影（timeline JOIN message）：消息全量 + 投递序号
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TimelineMessagePO extends MessagePO {

    private Long deliverySeq;
}
