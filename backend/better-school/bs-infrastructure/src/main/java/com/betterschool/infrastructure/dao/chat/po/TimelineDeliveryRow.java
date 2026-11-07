package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

/**
 * 增量补拉的投递行（timeline 单表扫描）：msg_id + 投递序号；消息全量由仓储按 msg_id 集
 * 二次批量回查拼装（原 selectTimelineAfter JOIN message 的多查形态）。
 */
@Data
public class TimelineDeliveryRow {

    private String msgId;
    private Long deliverySeq;
}
