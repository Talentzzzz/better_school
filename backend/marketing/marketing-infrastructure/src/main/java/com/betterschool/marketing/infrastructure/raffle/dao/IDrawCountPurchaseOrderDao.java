package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawCountPurchaseOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖次数购买单表（draw_count_purchase_order）Mapper：一表一 DAO（ADR-0014）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawCountPurchaseOrderMapper.xml。
 * 状态机口径（ADR-0016 票 C 三段式）：0 CREATED（单已落、扣分结果未知）→ 1 DONE，2 DEAD。
 */
@Mapper
public interface IDrawCountPurchaseOrderDao {

    /** 落单：撞 uk_purchase_order_id 抛 DuplicateKeyException（并发同 token 的唯一互斥点，不用 INSERT IGNORE） */
    int insertOrder(DrawCountPurchaseOrderPO orderPO);

    /** 按购买单业务 ID 查（Stripe 幂等重放入口） */
    DrawCountPurchaseOrderPO selectByPurchaseOrderId(@Param("purchaseOrderId") String purchaseOrderId);

    /** 到期待收敛单：state=0 且 next_retry_at 为空或已到点，按 id 升序（走 idx_state_next_retry） */
    List<DrawCountPurchaseOrderPO> listDue(@Param("limit") int limit);

    /** 抢占式认领：state=0 且到点才生效，递增 retry_count 并写退避时点；0 行 = 已被抢/状态漂移 */
    int claim(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 置 DONE：state <> 1 才生效（CREATED 与 DEAD 都可达）；0 行 = 已完成（幂等跳过） */
    int finish(@Param("purchaseOrderId") String purchaseOrderId);

    /** 置 DEAD：state=0 才生效；0 行 = 状态已变，跳过 */
    int markDead(@Param("purchaseOrderId") String purchaseOrderId);
}
