package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.Timeline;
import com.betterschool.infrastructure.dao.chat.po.TimelineDeliveryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * timeline 表 DAO：SQL 定义在 app 模块 resources/mybatis/mapper/TimelineMapper.xml。
 * 投递表只插不改；timeline.msg_id 外联的是消息业务 ID（message.msg_id），不是物理主键
 */
@Mapper
public interface ITimelineDao {

    int insertTimelines(@Param("timelines") List<Timeline> timelineList);

    /** 增量补拉两段查之①（timeline 单表）：本人投递行（msg_id + delivery_seq 升序）；消息全量仓储回查拼装 */
    List<TimelineDeliveryRow> selectTimelineRows(@Param("userId") String userId,
                                                   @Param("afterDeliverySeq") long afterDeliverySeq,
                                                   @Param("limit") int limit);
}
