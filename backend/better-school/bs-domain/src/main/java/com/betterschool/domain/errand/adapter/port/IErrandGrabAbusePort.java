package com.betterschool.domain.errand.adapter.port;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 跑腿防占单：主动放弃的滑动计数与抢单冷却（票 05）。
 *
 * <p>它是「判定加速层」，不是事实源——事实源永远是 {@code errand_order_event}
 * （{@code actor_id + LOCK_ABANDONED + create_time}）；Redis 只是热路径上的计数与冷却标记，
 * 不可用/降级时回落事件表（宽恕方向安全，不引入对账补偿）。
 *
 * <p>Redis 不参与状态迁移互斥：抢单原子性仍由条件 UPDATE 保证，本端口只在
 * {@code grabOrder} 前置读冷却、在放弃主效应成功后写计数。
 */
public interface IErrandGrabAbusePort {

    /**
     * 记一次「主动放弃」到该用户的滑动窗口（score = 放弃时刻），返回窗口内计数。
     * 写点必须在放弃主效应（条件 UPDATE + 事件）成功之后调用：Redis 写失败 = 少计 = 宽恕。
     *
     * @param windowFloor 窗口下界（now − 窗口时长），早于它的旧放弃在本次写入时被裁掉
     */
    long recordAbandon(String userId, String orderId, int lockSeq, LocalDateTime windowFloor);

    /**
     * 置冷却标记（{@code SET NX EX}，幂等、不叠加延长）。
     *
     * @return 生效的冷却到期时刻：本次刚置上为新到期时刻，已存在（冷却中）返回既有到期时刻
     */
    LocalDateTime startCooldown(String userId, Duration cooldown);

    /** 清空该用户的滑动窗口计数（触发冷却时重置——否则冷却一结束那 3 次还在，会立刻再触发） */
    void resetWindow(String userId);

    /**
     * 冷却判定（{@code grabOrder} 前置）：返回冷却到期时刻，未冷却返回 {@code null}。
     * Redis 报错时回落事件表：窗口内主动放弃达 {@code threshold} 且最近一次放弃仍在冷却期内，
     * 则返回估计到期时刻；否则放行。回落本身也失败 → 放行（宽恕方向安全）。
     *
     * @param windowFloor 窗口下界（now − 窗口时长）
     * @param threshold   窗口内主动放弃达此次数即判冷却
     */
    LocalDateTime cooldownUntil(String userId, LocalDateTime windowFloor, int threshold, Duration cooldown);
}
