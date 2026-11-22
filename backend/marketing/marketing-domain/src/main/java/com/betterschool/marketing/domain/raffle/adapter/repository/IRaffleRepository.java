package com.betterschool.marketing.domain.raffle.adapter.repository;

import com.betterschool.marketing.domain.raffle.model.aggregate.DrawRecordAggregate;
import com.betterschool.marketing.domain.raffle.model.entity.DrawActivityEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawOrphanPrereserveOrderEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordDetailEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawReconRoundEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawReconVarianceEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyAwardEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyWeightEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordVO;
import com.betterschool.marketing.domain.raffle.model.valobj.PrizeCatalogVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UserPrizeVO;
import com.betterschool.marketing.types.raffle.DrawCountReasonType;
import com.betterschool.marketing.types.raffle.DrawReconVarianceState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 抽奖域唯一仓储（一域一仓储）：决策侧配置读 + 决策数据 Redis 读写、读侧翻页视图、
 * 落库写面、发货状态机写面、抽奖次数桶账务五张面合成一个接口。前四张面原本各有一个接口，
 * 但都建在同一个 {@code IRaffleDao} 上，故合并为一个仓储、实现体原样搬；次数桶一张面随
 * 「次数与抽奖同域」从原 {@code IDrawCountRepository} 并入。
 * <p>
 * 事务：一次操作内的多步读写由仓储自己用 {@code TransactionTemplate}（REQUIRED）收成一个原子用例
 * ——{@link #insertDrawRecord}（记录 + 明细 + 中奖事件 outbox 同事务）、
 * {@link #advanceState}（状态推进 + 动作明细同事务）、{@link #creditDrawCountBucket}（造桶即插桶）
 * 与 {@link #deductDrawCountForDraw}（幂等预检 + FIFO 扣次 + 消费明细同事务）；
 * 域服务不再注入 {@code TransactionTemplate}。
 */
public interface IRaffleRepository {

    /** 特型奖品位槽名：黑名单拦截奖（= draw_strategy_award.type 值） */
    String SLOT_BLACKLIST = "blacklist";
    /** 特型奖品位槽名：库存兜底奖（= draw_strategy_award.type 值，票 13 库存扣失败时取用） */
    String SLOT_FALLBACK = "fallback";

    // ---- 配置读（DB，决策侧） ----

    /** 按活动业务 ID 查活动（不存在返回 null） */
    DrawActivityEntity queryActivityEntityByActivityId(String activityId);

    /** 按策略业务 ID 查策略（rule 列已切开为节点码序列；不存在返回 null） */
    DrawStrategyEntity queryStrategyEntityByStrategyId(String strategyId);

    /** 该策略的全部奖品位（含 normal / fallback / blacklist，概率口径以 award_rate 为准） */
    List<DrawStrategyAwardEntity> queryAwardListByStrategyId(String strategyId);

    /** 该策略的全部权重档（weights 列已切开为 award_id 列表），按 count 升序 */
    List<DrawStrategyWeightEntity> queryWeightListByStrategyId(String strategyId);

    /** 用户在该策略下的累计抽数 = draw_record 中该 user_id + strategy_id 的行数（权重档命中判据） */
    int queryUserDrawCount(String strategyId, String userId);

    // ---- 决策数据（Redis） ----

    /**
     * 整体重建一张概率表（预热用）：key 口径见 {@code types.raffle.Constants.RAFFLE_TABLE_KEY}，
     * 恒 10000 槽（槽位 0..9999 → awardId）。先删旧 key 再写，保证配置变更重预热后无陈旧槽位。
     */
    void cacheRaffleTable(String key, Map<Integer, String> table);

    /** 读概率表；未预热 / key 不存在返回空 map */
    Map<Integer, String> getRaffleTable(String key);

    /** 黑名单成员判定（bucket 存在即命中）；集合由风控侧写入，本域无黑名单用户表 */
    boolean isUserBlacklisted(String strategyId, String userId);

    /** 缓存特型奖品位 awardId（slot = blacklist / fallback，预热时自配置表灌入） */
    void cacheAwardId(String strategyId, String slot, String awardId);

    /** 读特型奖品位 awardId；缓存未命中回查 DB（type = slot 的第一行，按 sort,id），都没有返回 null */
    String queryAwardIdBySlot(String strategyId, String slot);

    /** 标记策略预热完成（值 = 完成时刻 ISO 文本，可观测）；不存在该 key 即降级态 */
    void cacheWarmupState(String strategyId);

    /** 预热态可查：true = 已预热（预热态），false = 降级态 */
    boolean isWarmed(String strategyId);

    // ---- 读侧（主链路幂等重放 / 活动 / 积分面额 / 对外翻页视图） ----

    /** 当前活动 = draw_activity 最新一行（单活动运营口径；无活动返回 null） */
    DrawActivityEntity queryCurrentActivity();

    /** 按记录业务 ID 查（幂等重放 / 并发去重 / 状态机判定起点）；不存在返回 null */
    DrawRecordEntity queryRecordByRecordId(String recordId);

    /** 积分面额：奖品位对应虚拟池的 value（仅 type=POINTS 池；award_id → inv_id → 池两步），无匹配返回 null */
    Integer queryPointsValueByAwardId(String awardId);

    /** 奖品位是否存在（管理/投放侧分配 usecase 的入参校验用） */
    boolean existsAward(String awardId);

    /** 今日已抽数 = draw_record 该用户当日行数 */
    int countTodayDrawn(String userId);

    /**
     * 奖品目录视图（award-inventory 两层模型）：奖品位 + 奖品库存账户批量回查的展示口径
     * （remaining/total 读账户，池 surplus 已被分配转走）；积分面额仍读池配置
     * （award_id → inv_id → type=POINTS 池的 value），账户/池行缺失或 surplus&lt;0 = 不限量落 null。
     */
    List<PrizeCatalogVO> queryPrizeCatalog(String strategyId);

    /** 我的中奖记录：恒过滤 THANKS，create_time DESC */
    long countDrawRecords(String userId);

    List<DrawRecordVO> listDrawRecords(String userId, int offset, int limit);

    /** 我的奖品：award_type != THANKS，可按 state 集合过滤（空 = 全部），create_time DESC */
    long countMyPrizes(String userId, List<String> states);

    List<UserPrizeVO> listMyPrizes(String userId, List<String> states, int offset, int limit);

    // ---- 落库写面 ----

    /**
     * 同事务落 draw_record 行 + draw_record_detail(DRAW) 动作行 + 中奖事件 outbox 行。恒返回 true
     * （失败以异常表达）：detail 撞 uk_record_type 视为已落过忽略；record 撞 uk_record_id 抛
     * DuplicateKeyException——重放判定（先查后插 / 捕获降级）由调用方编排层负责。
     */
    boolean insertDrawRecord(DrawRecordAggregate aggregate);

    // ---- 发货状态机写面 ----

    /**
     * 一次（或一步到底的连续两次）状态推进，**同一事务**内完成：
     * 按顺序对每个动作做「条件更新 {@code state: current → action.targetState}」+ {@code INSERT IGNORE} 该动作明细。
     * 任一守卫落空（返回 0）即整笔回滚并返回 false —— 幂等重放 / 状态已不在起点时就是这条路径。
     *
     * @param details 按推进顺序排列的动作明细（每条的 type 决定目标状态），长度 1 或 2
     * @return true = 本次真的推进了；false = 起点状态不匹配（重复投递 / 已终态）
     */
    boolean advanceState(String userId, String recordId, String fromState, List<DrawRecordDetailEntity> details);

    // ---- 抽奖次数桶账务（与积分桶同构：桶即流水、无余额列、过期懒判） ----

    /**
     * 一次次数入账，**同一事务**内完成：造桶（bucket_id 走统一 IdGenerator 发号、expire_at =
     * 事务内时点 + 次数有效期）→ 插桶（撞 {@code uk_user_reason_ref} 即幂等命中）。
     * 幂等键落在 uk_user_reason_ref 上，重复入账不重复加次数；「幂等跳过」的日志由域服务按业务口径打。
     *
     * @param reasonType REDEEM（积分购买入账）或 REBATE（行为返利）
     * @return true = 本次真的入账了；false = 这笔入账已记过（幂等跳过）
     */
    boolean creditDrawCountBucket(String userId, int amount, DrawCountReasonType reasonType, String refId);

    /**
     * 一次抽奖扣次，**同一事务**内完成：幂等预检（该抽奖记录已扣过即跳过）→ FIFO 取最早可用桶 →
     * 落一行明细并把该桶 remain 减 1。桶不足返回 false，一行都没动（无半扣）。
     */
    boolean deductDrawCountForDraw(String userId, String recordId);

    /** 剩余次数 = 未过期且未扣完的桶 remain 之和（没有余额列）；对外读与消费路径同口径 */
    int sumAvailableDrawCount(String userId);

    /** 抽奖次数有效期天数（{@code draw_count_config} 单行，种子 id=1 / 30 天）；缺行抛系统异常 */
    int drawCountValidDays();

    /** 更新抽奖次数有效期天数（单行，id=1）；缺行视为系统错误 */
    void updateDrawCountValidDays(int days);

    // ---- 购买单（ADR-0014，订单兼 outbox：幂等入口 + 状态机载体 + 对账源） ----

    /**
     * 落一行购买单（{@code state = DEDUCTED}）。事务边界在调用方（port 适配器的组合事务）：
     * 本方法用 {@code TransactionTemplate} REQUIRED 并入外层，与积分扣分同一物理事务提交/回滚——
     * 「单在 ⇒ 分已扣」由调用方保证，本方法不单独兜底。
     * 撞 {@code uk_purchase_order_id} 抛 {@code DuplicateKeyException}（并发同 token 的唯一互斥点）。
     */
    boolean insertPurchaseOrder(DrawCountPurchaseOrderEntity order);

    /** 按购买单业务 ID 查（Stripe 幂等重放入口）；不存在返回 null */
    DrawCountPurchaseOrderEntity queryPurchaseOrder(String purchaseOrderId);

    /**
     * 到期待补偿的购买单：{@code state = DEDUCTED} 且 {@code next_retry_at} 为空或已到点，
     * 按 id 升序（FIFO），上限 {@code limit}。走 {@code idx_state_next_retry}，正常时点扫描只碰分钟级窗口内的少量行。
     */
    List<DrawCountPurchaseOrderEntity> listDuePurchaseOrders(int limit);

    /**
     * 抢占式认领一笔待补偿单：条件更新 {@code state = DEDUCTED 且到点} 才生效，
     * 同时递增 {@code retry_count}、写 {@code next_retry_at}（退避到下一轮）。
     * 返回 false = 已被其他实例抢占或状态已不在起点，跳过即可。
     */
    boolean claimPurchaseOrder(Long id, LocalDateTime nextRetryAt);

    /**
     * 入账完成置 {@code DONE}（条件更新 {@code state <> DONE} 才生效）——
     * 从 DEDUCTED 与 DEAD 都可达：DEAD 行被人工重置或客户端重试补齐后同样收敛。
     * 返回 false = 已是 DONE（幂等跳过）。
     */
    boolean finishPurchaseOrder(String purchaseOrderId);

    /** 重试耗尽置 {@code DEAD}（条件更新 {@code state = DEDUCTED}）；返回 false = 状态已变，跳过 */
    boolean markPurchaseOrderDead(Long id);

    // ---- 孤儿预扣对账单（spec User Stories 14–16：扫描种单 + 重试状态机 + 死信） ----

    /**
     * 种一张孤儿预扣对账单：{@code INSERT IGNORE} 撞 {@code uk_record_id} 静默跳过——
     * 同一孤儿被多个扫描周期 / 多实例重复种出是常态，DONE/DEAD 的对账单不再被重新种。
     * 返回 true = 本周期真的种出了（新孤儿）。
     */
    boolean insertOrphanPrereserveOrder(DrawOrphanPrereserveOrderEntity order);

    /**
     * 到期待收敛的对账单：{@code state = ORPHAN} 且 {@code next_retry_at} 为空或已到点，
     * 按 id 升序（FIFO），上限 {@code limit}。走 {@code idx_state_next_retry}。
     */
    List<DrawOrphanPrereserveOrderEntity> listDueOrphanPrereserveOrders(int limit);

    /**
     * 抢占式认领一张待收敛单：条件更新 {@code state = ORPHAN 且到点} 才生效，同时递增
     * {@code retry_count}、写 {@code next_retry_at}（退避到下一轮）。
     * 返回 false = 已被其他实例抢占或状态已不在起点，跳过即可。
     */
    boolean claimOrphanPrereserveOrder(Long id, LocalDateTime nextRetryAt);

    /**
     * 收敛完成置 {@code DONE}（条件更新 {@code state <> DONE} 才生效）——从 ORPHAN 与 DEAD
     * 都可达：DEAD 行人工 SQL 重置后同样自愈。返回 false = 已是 DONE（幂等跳过）。
     */
    boolean finishOrphanPrereserveOrder(String recordId);

    /** 重试耗尽置 {@code DEAD}（条件更新 {@code state = ORPHAN}）；返回 false = 状态已变，跳过 */
    boolean markOrphanPrereserveOrderDead(Long id);

    /** 按抽奖记录业务 ID 查孤儿对账单（对账轮正向配对分流判据：单在即逐笔收敛链路已接管）；不存在返回 null */
    DrawOrphanPrereserveOrderEntity queryOrphanPrereserveOrderByRecordId(String recordId);

    /** 死信单点名扫描：state=DEAD，按 id 升序，上限 limit（对账轮周期性点名的读入口） */
    List<DrawOrphanPrereserveOrderEntity> listDeadOrphanPrereserveOrders(int limit);

    /**
     * 按抽奖记录反查扣次明细的用户 ID（走 {@code draw_count_detail.idx_ref}）：
     * 孤儿种单时取 {@code user_id} 快照——孤儿口径下扣次必然已发生（主链路先扣次后预扣），
     * 明细行不在 = 数据破坏，返回 null 由调用方留 ERROR 跳过。
     */
    String queryUserIdByDrawCountRef(String refId);
    // ---- 对账轮写面（每日全量对账轮，纯旁路：只读业务表、只写差错表/轮次表） ----

    /**
     * 登记差错：INSERT IGNORE，撞 uk_round_check_biz（轮次, 检查类型, 业务单号）返回 false =
     * 本轮已登记过（调度重复触发 / 重跑零副作用）。
     */
    boolean insertReconVariance(DrawReconVarianceEntity varianceEntity);

    /** 该轮次的全部 PENDING 差错（下一轮复查的二次核对输入），按 id 升序 */
    List<DrawReconVarianceEntity> listPendingReconVariancesByRound(String roundNo);

    /** 二次核对确认：state PENDING→CONFIRMED；false = 已不在 PENDING（并发推进 / 复查已消失），调用方跳过 */
    boolean confirmReconVariance(Long id);

    /** 平账分流·自动修复（复用孤儿种单入口补种完成后调用）：state CONFIRMED→AUTO_FIXED，写 fixed_time */
    boolean markReconVarianceAutoFixed(Long id);

    /** 复查消失（日切边界假差错）：state PENDING→FALSE_ALARM，写 finish_time */
    boolean markReconVarianceFalseAlarm(Long id);

    /** 平账分流·人工处理完成（守恒破坏 / 反向缺失告警后人工核对）：state CONFIRMED→RESOLVED_MANUAL，写 finish_time */
    boolean markReconVarianceResolvedManual(Long id);

    /** 该轮次登记差错总数（终轮回填 variance_total） */
    long countReconVariancesByRound(String roundNo);

    /** 该轮次指定状态的差错数（终轮回填 confirmed_total / auto_fixed_total 等计数） */
    long countReconVariancesByRoundAndState(String roundNo, DrawReconVarianceState state);

    /** 起轮：INSERT IGNORE，撞 uk_round_no 返回 false = 本轮已起过 */
    boolean insertReconRound(DrawReconRoundEntity roundEntity);

    /** 终轮：按 round_no 回填结束时间与三项计数（一轮一行，无状态机） */
    boolean finishReconRound(String roundNo, LocalDateTime scanEndTime,
                             int varianceTotal, int confirmedTotal, int autoFixedTotal);
}
