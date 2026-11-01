package com.betterschool.domain.chat.model.valobj;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 补拉条目：消息全量 + per-user 投递序号（增量补拉游标，timeline.uk_user_delivery）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimelineItemVO {

    private MessageEntity message;

    private Long deliverySeq;
}
