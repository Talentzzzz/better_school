package com.betterschool.marketing.domain.raffle.adapter.repository;

import com.betterschool.marketing.domain.raffle.model.aggregate.DrawRecordAggregate;
import com.betterschool.marketing.domain.raffle.model.entity.DrawActivityEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordDetailEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyAwardEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyWeightEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordVO;
import com.betterschool.marketing.domain.raffle.model.valobj.PrizeCatalogVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UserPrizeVO;

import java.util.List;
import java.util.Map;

/**
 * 抽奖域唯一仓储（一域一仓储）：决策侧配置读 + 决策数据 Redis 读写、读侧翻页视图、
 * 落库写面、发货状态机写面四张面合成一个接口。四张面原本各有一个接口，但都建在同一个
 * {@code IRaffleDao} 上，故合并为一个仓储、实现体原样搬。
 * <p>
 * 事务：一次操作内的多步读写由仓储自己用 {@code TransactionTemplate}（REQUIRED）收成一个原子用例
 * ——{@link #insertDrawRecord}（记录 + 明细 + 中奖事件 outbox 同事务）与
 * {@link #advanceState}（状态推进 + 动作明细同事务）；域服务不再注入 {@code TransactionTemplate}。
 */
public interface IRaffleRepository {

    /** 特型奖品位槽名：黑名单拦截奖（= draw_strategy_award.type 值） */
    String SLOT_BLACKLIST = "blacklist";
    /** 特型奖品位槽名：库存兜底奖（= draw_strategy_award.type 值，票 13 库存扣失败时取用） */
    String SLOT_FALLBACK = "fallback";

    // ---- 配置读（DB，决策侧） ----

    /** 按活动业务 ID 查活动（不存在返回 null） */
    DrawActivityEntity queryActivityEntityByActivityId(Long activityId);

    /** 按策略业务 ID 查策略（rule 列已切开为节点码序列；不存在返回 null） */
    DrawStrategyEntity queryStrategyEntityByStrategyId(Long strategyId);

    /** 该策略的全部奖品位（含 normal / fallback / blacklist，概率口径以 award_rate 为准） */
    List<DrawStrategyAwardEntity> queryAwardListByStrategyId(Long strategyId);

    /** 该策略的全部权重档（weights 列已切开为 award_id 列表），按 count 升序 */
    List<DrawStrategyWeightEntity> queryWeightListByStrategyId(Long strategyId);

    /** 用户在该策略下的累计抽数 = draw_record 中该 user_id + strategy_id 的行数（权重档命中判据） */
    int queryUserDrawCount(Long strategyId, String userId);

    // ---- 决策数据（Redis） ----

    /**
     * 整体重建一张概率表（预热用）：key 口径见 {@code types.raffle.Constants.RAFFLE_TABLE_KEY}，
     * 恒 10000 槽（槽位 0..9999 → awardId）。先删旧 key 再写，保证配置变更重预热后无陈旧槽位。
     */
    void cacheRaffleTable(String key, Map<Integer, Long> table);

    /** 读概率表；未预热 / key 不存在返回空 map */
    Map<Integer, Long> getRaffleTable(String key);

    /** 黑名单成员判定（bucket 存在即命中）；集合由风控侧写入，本域无黑名单用户表 */
    boolean isUserBlacklisted(Long strategyId, String userId);

    /** 缓存特型奖品位 awardId（slot = blacklist / fallback，预热时自配置表灌入） */
    void cacheAwardId(Long strategyId, String slot, Long awardId);

    /** 读特型奖品位 awardId；缓存未命中回查 DB（type = slot 的第一行，按 sort,id），都没有返回 null */
    Long queryAwardIdBySlot(Long strategyId, String slot);

    /** 标记策略预热完成（值 = 完成时刻 ISO 文本，可观测）；不存在该 key 即降级态 */
    void cacheWarmupState(Long strategyId);

    /** 预热态可查：true = 已预热（预热态），false = 降级态 */
    boolean isWarmed(Long strategyId);

    // ---- 读侧（主链路幂等重放 / 活动 / 积分面额 / 对外翻页视图） ----

    /** 当前活动 = draw_activity 最新一行（单活动运营口径；无活动返回 null） */
    DrawActivityEntity queryCurrentActivity();

    /** 按记录业务 ID 查（幂等重放 / 并发去重 / 状态机判定起点）；不存在返回 null */
    DrawRecordEntity queryRecordByRecordId(Long recordId);

    /** 积分面额：virtual_inventory_quota.value（仅 type=POINTS 行），无匹配返回 null */
    Integer queryPointsValueByInvId(Long invId);

    /** 今日已抽数 = draw_record 该用户当日行数 */
    int countTodayDrawn(String userId);

    /** 奖品目录视图：奖品位 LEFT JOIN 两段库存台账的展示口径（remaining/total），inv_id=0 = 不限量 */
    List<PrizeCatalogVO> queryPrizeCatalog(Long strategyId);

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
    boolean advanceState(String userId, Long recordId, String fromState, List<DrawRecordDetailEntity> details);
}
