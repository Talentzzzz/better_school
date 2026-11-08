package com.betterschool.infrastructure.dao.errand;

import com.betterschool.infrastructure.dao.errand.po.ErrandOrderEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 跑腿订单状态流转事件 Mapper（errand_order_event 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ErrandOrderEventMapper.xml
 */
@Mapper
public interface IErrandOrderEventDao {

    /** 订单状态流转事件落表（同事务，只插入） */
    int insertOrderEvent(ErrandOrderEvent event);

    /** 窗口内主动放弃次数（actor_id + LOCK_ABANDONED + create_time >= since） */
    int countAbandonsSince(@Param("actorId") String actorId, @Param("since") LocalDateTime since);

    /** 窗口内最近一次主动放弃时刻（无则 null） */
    LocalDateTime selectLatestAbandonTimeSince(@Param("actorId") String actorId, @Param("since") LocalDateTime since);
}
