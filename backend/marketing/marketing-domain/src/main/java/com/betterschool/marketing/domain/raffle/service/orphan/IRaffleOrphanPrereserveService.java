package com.betterschool.marketing.domain.raffle.service.orphan;

/**
 * 孤儿预扣对账服务（spec User Stories 14–16）：抽奖「预扣 → 落库」跨合并器事务的缝被中断时
 * （预扣明细在、{@code draw_record} 不在、次数已扣），两条收敛路径让孤儿预扣在一个扫描周期内收敛——
 * <ul>
 *   <li>决策可重放（活动仍有效）→ 以原幂等键重放抽奖落库后半段（不重复扣次数、预扣撞唯一键按已扣
 *       回放）；决策重跑可能得到与原决策不同的奖品，接受该漂移（原奖品的预扣按回补口径归还）；</li>
 *   <li>决策不可重放（活动已结束 / 下线）→ 库存回补（PHYSICAL {@code waiting → surplus} /
 *       VIRTUAL {@code issued → surplus}，经库存端口）+ 抽奖次数退还（次数桶幂等键入账，
 *       退还本身幂等，不产生新的两步缝）。</li>
 * </ul>
 * 重试口径照 {@code drawCountPurchaseScan}：0/30 cron、条件更新抢占、指数退避（1/2/4/8/16 分钟）、
 * 耗尽进死信（只 ERROR 日志 + 人工 SQL 重置，不建告警通路）。状态承载在对账单表
 * {@code draw_orphan_prereserve_order}（种单 INSERT IGNORE + 重试状态机）。
 */
public interface IRaffleOrphanPrereserveService {

    /**
     * 对账一轮（job 的唯一入口）：先种单（扫孤儿预扣 → INSERT IGNORE 对账单），
     * 再补偿（listDue → 抢占 → 收敛）。返回本轮收敛的对账单数。
     */
    int reconcile(int limit);

    /**
     * 种单：扫库存预扣明细中「{@code flow_type=1 AND biz_type='LOTTERY_DRAW'} 且 {@code biz_id}
     * 在 {@code draw_record} 无对应行、且预扣发生早于补偿阈值（10 分钟）」的孤儿，
     * 逐行 INSERT IGNORE 种对账单（user_id 快照自 {@code draw_count_detail.idx_ref} 反查）。
     * 返回本轮新种出的对账单数。
     */
    int seedOrphanPrereserves(int limit);

    /**
     * 补偿：到期待收敛单逐笔抢占（条件更新递增 {@code retry_count} + 写退避时点）后收敛；
     * 收敛失败按退避重试，耗尽（第 5 次尝试仍失败）置 DEAD 留人工。返回本轮收敛笔数。
     */
    int compensateDueOrders(int limit);
}
