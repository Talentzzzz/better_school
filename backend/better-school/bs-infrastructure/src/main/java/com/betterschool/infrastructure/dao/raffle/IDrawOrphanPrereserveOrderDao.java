package com.betterschool.infrastructure.dao.raffle;

import com.betterschool.infrastructure.dao.raffle.po.DrawOrphanPrereserveOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿预扣对账单表（draw_orphan_prereserve_order）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawOrphanPrereserveOrderMapper.xml。
 * <p>
 * 与购买单 DAO 的唯一形状差异：种单用 {@code INSERT IGNORE}——种单是批量扫描动作，
 * 同一孤儿被多个扫描周期 / 多实例重复种出是常态，撞 {@code uk_record_id} 静默跳过
 * （DONE/DEAD 的对账单不再被重新种）。
 */
@Mapper
public interface IDrawOrphanPrereserveOrderDao {

    /** 种单：撞 uk_record_id 返回 0 = 该孤儿的对账单已存在（本周期幂等跳过） */
    int insertIgnoreOrder(DrawOrphanPrereserveOrderPO orderPO);

    /** 到期待收敛单：state=0 且 next_retry_at 为空或已到点，按 id 升序（走 idx_state_next_retry） */
    List<DrawOrphanPrereserveOrderPO> listDue(@Param("limit") int limit);

    /** 抢占式认领：state=0 且到点才生效，递增 retry_count 并写退避时点；0 行 = 已被抢/状态漂移 */
    int claim(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 置 DONE：state <> 1 才生效（ORPHAN 与 DEAD 都可达，人工重置后自愈）；0 行 = 已收敛（幂等跳过） */
    int finish(@Param("recordId") String recordId);

    /** 置 DEAD：state=0 才生效；0 行 = 状态已变，跳过 */
    int markDead(@Param("id") Long id);
}
