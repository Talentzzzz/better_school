package com.betterschool.marketing.infrastructure.raffle.adapter.repository.raffle;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.aggregate.DrawRecordAggregate;
import com.betterschool.marketing.domain.raffle.model.entity.DrawActivityEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawCountBucketEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordDetailEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyAwardEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyWeightEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordActionVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordVO;
import com.betterschool.marketing.domain.raffle.model.valobj.PrizeCatalogVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UserPrizeVO;
import com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageStore;
import com.betterschool.marketing.infrastructure.inventory.dao.IAwardInventoryDao;
import com.betterschool.marketing.infrastructure.inventory.dao.IVirtualInventoryQuotaDao;
import com.betterschool.marketing.infrastructure.inventory.dao.po.AwardInventoryPO;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawActivityDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawCountConfigDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawCountDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawCountDetailDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawCountPurchaseOrderDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawRecordDetailDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawStrategyAwardDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawStrategyDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawStrategyWeightDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IRaffleDao;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawActivityPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawCountBucketPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawCountDetailPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawCountPurchaseOrderPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordDetailPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordViewPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyAwardPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyWeightPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawUserPrizeViewPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.ExchangeDetailRowPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.VirtualQuotaRowPO;
import com.betterschool.marketing.types.common.Constants;
import com.betterschool.marketing.types.id.IIdGenerator;
import com.betterschool.marketing.types.raffle.DrawCountPurchaseState;
import com.betterschool.marketing.types.raffle.DrawCountReasonType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 抽奖域唯一仓储实现：五张面（决策侧配置读 + 决策数据 Redis 读写、读侧翻页视图、落库写面、
 * 发货状态机写面、抽奖次数桶账务）原样合成，数据访问按表分落各 DAO（一表一 DAO；读侧批量回查
 * 直接注入库存域的 {@code IAwardInventoryDao} / {@code IVirtualInventoryQuotaDao}）；跨表 /
 * 带 outbox / 桶与明细的写面用 {@code TransactionTemplate}（REQUIRED，可并入调用方事务）。
 * <p>
 * 次数桶四张表（bucket / detail / config）随「抽奖次数与抽奖同域」并入本仓储，原
 * {@code DrawCountRepository} 的方法体原样搬入（入账、扣次两处事务仍由本类承担）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RaffleRepository implements IRaffleRepository {

    private final IRaffleDao raffleDao;
    private final IDrawActivityDao drawActivityDao;
    private final IDrawStrategyDao drawStrategyDao;
    private final IDrawStrategyAwardDao drawStrategyAwardDao;
    private final IDrawStrategyWeightDao drawStrategyWeightDao;
    private final IDrawRecordDetailDao drawRecordDetailDao;
    private final IDrawCountDao drawCountDao;
    private final IDrawCountDetailDao drawCountDetailDao;
    private final IDrawCountPurchaseOrderDao drawCountPurchaseOrderDao;
    private final IDrawCountConfigDao drawCountConfigDao;
    private final IVirtualInventoryQuotaDao virtualInventoryQuotaDao;
    private final IAwardInventoryDao awardInventoryDao;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final OutboxMessageStore outboxMessageStore;
    private final IIdGenerator idGenerator;

    // ---------------------------------------------------------------- 配置读（决策侧）

    @Override
    public DrawActivityEntity queryActivityEntityByActivityId(String activityId) {
        return toActivityEntity(drawActivityDao.selectActivityByActivityId(activityId));
    }

    @Override
    public DrawStrategyEntity queryStrategyEntityByStrategyId(String strategyId) {
        DrawStrategyPO strategyPO = drawStrategyDao.selectStrategyByStrategyId(strategyId);
        if (strategyPO == null) {
            return null;
        }
        DrawStrategyEntity entity = new DrawStrategyEntity();
        entity.setId(strategyPO.getId());
        entity.setStrategyId(strategyPO.getStrategyId());
        entity.setStrategyDesc(strategyPO.getStrategyDesc());
        if (strategyPO.getRule() != null && !strategyPO.getRule().isBlank()) {
            entity.setRuleModels(Arrays.stream(strategyPO.getRule().split(","))
                    .map(String::trim).filter(segment -> !segment.isEmpty()).toList());
        } else {
            entity.setRuleModels(Collections.emptyList());
        }
        entity.setCreateTime(strategyPO.getCreateTime());
        entity.setUpdateTime(strategyPO.getUpdateTime());
        return entity;
    }

    @Override
    public List<DrawStrategyAwardEntity> queryAwardListByStrategyId(String strategyId) {
        return drawStrategyAwardDao.selectAwardListByStrategyId(strategyId).stream()
                .map(this::toAwardEntity).toList();
    }

    @Override
    public List<DrawStrategyWeightEntity> queryWeightListByStrategyId(String strategyId) {
        return drawStrategyWeightDao.selectWeightListByStrategyId(strategyId).stream().map(weightPO -> {
            DrawStrategyWeightEntity entity = new DrawStrategyWeightEntity();
            entity.setId(weightPO.getId());
            entity.setStrategyId(weightPO.getStrategyId());
            entity.setCount(weightPO.getCount());
            entity.setWeights(Arrays.stream(weightPO.getWeights().split(","))
                    .map(String::trim).filter(segment -> !segment.isEmpty())
                    .toList());
            entity.setCreateTime(weightPO.getCreateTime());
            entity.setUpdateTime(weightPO.getUpdateTime());
            return entity;
        }).toList();
    }

    @Override
    public int queryUserDrawCount(String strategyId, String userId) {
        return raffleDao.countUserRecords(strategyId, userId);
    }

    // ---------------------------------------------------------------- 决策数据（Redis）

    @Override
    public void cacheRaffleTable(String key, Map<Integer, String> table) {
        redissonClient.getMap(key).delete();
        RMap<Integer, String> raffleTable = redissonClient.getMap(key);
        raffleTable.putAll(table);
    }

    @Override
    public Map<Integer, String> getRaffleTable(String key) {
        RMap<Integer, String> raffleTable = redissonClient.getMap(key);
        return raffleTable.isEmpty() ? Collections.emptyMap() : raffleTable.readAllMap();
    }

    @Override
    public boolean isUserBlacklisted(String strategyId, String userId) {
        return blacklistUserBucket(strategyId, userId).isExists();
    }

    @Override
    public void cacheAwardId(String strategyId, String slot, String awardId) {
        awardBucket(strategyId, slot).set(awardId);
    }

    @Override
    public String queryAwardIdBySlot(String strategyId, String slot) {
        String cached = awardBucket(strategyId, slot).get();
        if (cached != null) {
            return cached;
        }
        String awardId = drawStrategyAwardDao.selectAwardIdBySlot(strategyId, slot);
        if (awardId != null) {
            cacheAwardId(strategyId, slot, awardId);
        }
        return awardId;
    }

    @Override
    public void cacheWarmupState(String strategyId) {
        redissonClient.getBucket(RAFFLE_WARMUP_STATE_KEY + strategyId, StringCodec.INSTANCE)
                .set(LocalDateTime.now().toString());
    }

    @Override
    public boolean isWarmed(String strategyId) {
        return redissonClient.getBucket(RAFFLE_WARMUP_STATE_KEY + strategyId, StringCodec.INSTANCE)
                .isExists();
    }

    // ---------------------------------------------------------------- 读侧

    @Override
    public DrawActivityEntity queryCurrentActivity() {
        return toActivityEntity(drawActivityDao.selectCurrentActivity());
    }

    @Override
    public DrawRecordEntity queryRecordByRecordId(String recordId) {
        return toRecordEntity(raffleDao.selectRecordByRecordId(recordId));
    }

    @Override
    public Integer queryPointsValueByAwardId(String awardId) {
        // 两步查（SQL 层零 JOIN）：award_id → 奖品位投放池配置（inv_id）→ type=POINTS 池的面额。
        // award-inventory 两层模型下账户只存 value_qty 数值，POINTS/DRAW_COUNT 的区分留在池配置里
        String invId = drawStrategyAwardDao.selectInvIdByAwardId(awardId);
        return invId == null ? null : virtualInventoryQuotaDao.selectPointsValueByInvId(invId);
    }

    @Override
    public boolean existsAward(String awardId) {
        return drawStrategyAwardDao.existsByAwardId(awardId);
    }

    @Override
    public int countTodayDrawn(String userId) {
        return raffleDao.countTodayDrawn(userId);
    }

    @Override
    public List<PrizeCatalogVO> queryPrizeCatalog(String strategyId) {
        // 三段查拼装（SQL 层零 JOIN）：①奖品行（同 sort,id 排序）；②奖品库存账户按 award_id 集批量回查
        // （remaining/total 的真相——award-inventory 两层模型，池 surplus 已被分配转走，不能再读池）；
        // ③虚拟池按 inv_id 集批量回查（只取 type=POINTS 的面额配置）。CASE 语义在 Java 收口——
        // remaining = 账户 surplus_qty、total = 虚拟 surplus+issued / 实物 surplus+waiting+issued，
        // surplus < 0（不限量）或账户行缺失（未投放）落 null 由 unlimited 表达
        List<DrawStrategyAwardPO> awards = drawStrategyAwardDao.selectAwardListByStrategyId(strategyId);
        if (awards.isEmpty()) {
            return List.of();
        }
        List<String> awardIds = awards.stream().map(DrawStrategyAwardPO::getAwardId).distinct().toList();
        Map<String, AwardInventoryPO> inventoryByAward = awardInventoryDao.selectByAwardIds(awardIds).stream()
                .collect(Collectors.toMap(AwardInventoryPO::getAwardId, row -> row, (a, b) -> a));
        List<String> invIds = awards.stream().map(DrawStrategyAwardPO::getInvId)
                .filter(invId -> invId != null && !"0".equals(invId))
                .distinct().toList();
        Map<String, VirtualQuotaRowPO> virtualByInv = invIds.isEmpty() ? Map.of()
                : virtualInventoryQuotaDao.selectVirtualQuotasByInvIds(invIds).stream()
                        .collect(Collectors.toMap(VirtualQuotaRowPO::getInvId, row -> row, (a, b) -> a));
        List<PrizeCatalogVO> prizes = new ArrayList<>(awards.size());
        for (DrawStrategyAwardPO award : awards) {
            PrizeCatalogVO vo = new PrizeCatalogVO();
            vo.setAwardId(award.getAwardId());
            vo.setAwardName(award.getAwardName());
            vo.setAwardType(award.getAwardType());
            vo.setLevel(award.getLevel());
            VirtualQuotaRowPO virtual = virtualByInv.get(award.getInvId());
            // 积分档面额（VIRTUAL + 池 type=POINTS，面额配置的真相仍在池），其余为 null
            vo.setPointsAmount("VIRTUAL".equals(award.getAwardType())
                    && virtual != null && "POINTS".equals(virtual.getType()) ? virtual.getValue() : null);
            Long remaining = null;
            Long total = null;
            AwardInventoryPO inventory = inventoryByAward.get(award.getAwardId());
            if (inventory != null && inventory.getSurplusQty() >= 0) {
                remaining = inventory.getSurplusQty();
                total = "PHYSICAL".equals(inventory.getType())
                        ? inventory.getSurplusQty() + inventory.getWaitingQty() + inventory.getIssuedQty()
                        : inventory.getSurplusQty() + inventory.getIssuedQty();
            }
            vo.setRemaining(remaining);
            vo.setTotal(total);
            vo.setUnlimited(remaining == null && total == null);
            prizes.add(vo);
        }
        return prizes;
    }

    @Override
    public long countDrawRecords(String userId) {
        return raffleDao.countDrawRecords(userId);
    }

    @Override
    public List<DrawRecordVO> listDrawRecords(String userId, int offset, int limit) {
        // 三段查拼装（SQL 层零 JOIN）：记录行单表分页 + 按 award_id（draw_record.inv_id 快照 = 奖品库存
        // 账户维度）集回查奖品位投放池配置 + 按 inv_id 集回查 type=POINTS 面额；
        // 缺行 = null（原 LEFT JOIN 空行口径）
        List<DrawRecordViewPO> rows = raffleDao.selectDrawRecordRows(userId, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> awardIds = rows.stream().map(DrawRecordViewPO::getInvId)
                .filter(invId -> invId != null)
                .distinct().toList();
        Map<String, String> invIdByAward = awardIds.isEmpty() ? Map.of()
                : drawStrategyAwardDao.selectAwardsByAwardIds(awardIds).stream()
                        .collect(Collectors.toMap(DrawStrategyAwardPO::getAwardId, DrawStrategyAwardPO::getInvId, (a, b) -> a));
        List<String> invIds = List.copyOf(invIdByAward.values());
        Map<String, Integer> pointValues = invIds.isEmpty() ? Map.of()
                : virtualInventoryQuotaDao.selectPointValuesByInvIds(invIds).stream()
                        .filter(row -> row.getValue() != null)
                        .collect(Collectors.toMap(VirtualQuotaRowPO::getInvId, VirtualQuotaRowPO::getValue, (a, b) -> a));
        return rows.stream().map(po -> {
            String invId = po.getInvId() == null ? null : invIdByAward.get(po.getInvId());
            return new DrawRecordVO(po.getRecordId(), po.getAwardId(), po.getAwardName(),
                    po.getAwardType(), po.getLevel(), invId == null ? null : pointValues.get(invId),
                    po.getCreateTime());
        }).toList();
    }

    @Override
    public long countMyPrizes(String userId, List<String> states) {
        return raffleDao.countMyPrizes(userId, states);
    }

    @Override
    public List<UserPrizeVO> listMyPrizes(String userId, List<String> states, int offset, int limit) {
        // 两段查拼装（原 draw_record LEFT JOIN EXCHANGE 明细，SQL 层零 JOIN）：记录行单表分页 +
        // 按 record_id 集批量回查兑换明细；缺行 = 还没兑换，claimedAt 落 null（原 LEFT JOIN 口径）
        List<DrawUserPrizeViewPO> rows = raffleDao.selectMyPrizeRows(userId, states, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, LocalDateTime> claimedAtByRecord = drawRecordDetailDao.selectExchangeDetailsByRecordIds(
                rows.stream().map(DrawUserPrizeViewPO::getRecordId).toList()).stream()
                .collect(Collectors.toMap(ExchangeDetailRowPO::getRecordId,
                        ExchangeDetailRowPO::getCreateTime, (a, b) -> a));
        return rows.stream().map(po -> new UserPrizeVO(po.getRecordId(), po.getAwardId(), po.getAwardName(),
                po.getAwardType(), po.getLevel(), po.getState(), po.getCreateTime(),
                claimedAtByRecord.get(po.getRecordId()))).toList();
    }

    // ---------------------------------------------------------------- 落库写面

    @Override
    public boolean insertDrawRecord(DrawRecordAggregate aggregate) {
        Boolean inserted = transactionTemplate.execute(status -> {
            DrawRecordPO recordPO = new DrawRecordPO();
            recordPO.setRecordId(aggregate.getRecord().getRecordId());
            recordPO.setUserId(aggregate.getRecord().getUserId());
            recordPO.setActivityId(aggregate.getRecord().getActivityId());
            recordPO.setStrategyId(aggregate.getRecord().getStrategyId());
            recordPO.setAwardId(aggregate.getRecord().getAwardId());
            recordPO.setAwardName(aggregate.getRecord().getAwardName());
            recordPO.setAwardDesc(aggregate.getRecord().getAwardDesc());
            recordPO.setAwardType(aggregate.getRecord().getAwardType());
            recordPO.setLevel(aggregate.getRecord().getLevel());
            recordPO.setInvId(aggregate.getRecord().getInvId());
            recordPO.setState(aggregate.getRecord().getState());
            recordPO.setCreateTime(aggregate.getRecord().getCreateTime());
            recordPO.setUpdateTime(aggregate.getRecord().getUpdateTime());
            raffleDao.insertDrawRecord(recordPO);

            DrawRecordDetailPO detailPO = new DrawRecordDetailPO();
            detailPO.setUserId(aggregate.getDetail().getUserId());
            detailPO.setRecordId(aggregate.getDetail().getRecordId());
            detailPO.setType(aggregate.getDetail().getType());
            detailPO.setCreateTime(aggregate.getDetail().getCreateTime());
            detailPO.setUpdateTime(aggregate.getDetail().getUpdateTime());
            // detail 撞 uk_record_type = 该记录 DRAW 已落过（重试重放），忽略；
            // record 撞 uk_record_id 抛 DuplicateKeyException，重放判定归调用方编排层
            drawRecordDetailDao.insertIgnoreRecordDetail(detailPO);

            // 中奖事件：THANKS 档不带事件（谢谢参与不发），其余与业务写同事务落 outbox
            if (aggregate.getDrawnEvent() != null) {
                outboxMessageStore.save(Constants.LOTTERY_DRAWN_TOPIC, aggregate.getDrawnEvent());
            }
            return true;
        });
        return Boolean.TRUE.equals(inserted);
    }

    // ---------------------------------------------------------------- 发货状态机写面

    @Override
    public boolean advanceState(String userId, String recordId, String fromState,
                                List<DrawRecordDetailEntity> details) {
        Boolean advanced = transactionTemplate.execute(status -> {
            String currentState = fromState;
            for (DrawRecordDetailEntity detail : details) {
                String toState = DrawRecordActionVO.fromCode(detail.getType()).getTargetState();
                if (raffleDao.updateRecordState(recordId, currentState, toState) == 0) {
                    // 起点不匹配：重复投递 / 已终态 → 整笔回滚（前一步的推进与明细一起消失）
                    status.setRollbackOnly();
                    return false;
                }
                DrawRecordDetailPO detailPO = new DrawRecordDetailPO();
                detailPO.setUserId(userId);
                detailPO.setRecordId(recordId);
                detailPO.setType(detail.getType());
                detailPO.setCreateTime(detail.getCreateTime());
                detailPO.setUpdateTime(detail.getUpdateTime());
                drawRecordDetailDao.insertIgnoreRecordDetail(detailPO);
                currentState = toState;
            }
            return true;
        });
        return Boolean.TRUE.equals(advanced);
    }

    // ---------------------------------------------------------------- 抽奖次数桶账务（同域）

    @Override
    public boolean creditDrawCountBucket(String userId, int amount, DrawCountReasonType reasonType, String refId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                insertDrawCountBucket(newDrawCountBucket(userId, amount, reasonType, refId, LocalDateTime.now()))));
    }

    @Override
    public boolean deductDrawCountForDraw(String userId, String recordId) {
        Boolean result = transactionTemplate.execute(status -> {
            if (drawCountDetailDao.selectDetailIdForUpdate(recordId) != null) {
                // 同一抽奖记录重放：幂等跳过，不重复扣次
                log.info("[RaffleRepository] 次数扣次明细已存在，幂等跳过 userId={}, recordId={}", userId, recordId);
                return Boolean.TRUE;
            }
            List<DrawCountBucketEntity> buckets = listAvailableDrawCountBucketsForUpdate(userId);
            if (buckets.isEmpty()) {
                // 一行都没动，事务空提交——不用回滚来表达业务失败
                return Boolean.FALSE;
            }
            // 每次抽奖恒扣 1 次：最早的可用桶必有 remain >= 1，单个桶即扣完，不跨桶
            DrawCountBucketEntity bucket = buckets.get(0);
            LocalDateTime now = LocalDateTime.now();
            DrawCountDetailPO detailPO = new DrawCountDetailPO();
            detailPO.setUserId(userId);
            detailPO.setBucketId(bucket.getBucketId());
            detailPO.setRefId(recordId);
            detailPO.setCreateTime(now);
            detailPO.setUpdateTime(now);
            // 流水先行：INSERT IGNORE 明细撞 uk = 这笔抽奖已被并发事务扣过 ⇒ 整事务回滚
            if (drawCountDetailDao.insertIgnoreDetail(detailPO) == 0) {
                log.warn("[RaffleRepository] 扣次明细撞唯一键，判定为重复抽奖 userId={}, bucketId={}, recordId={}",
                        userId, bucket.getBucketId(), recordId);
                status.setRollbackOnly();
                return null;
            }
            // 桶已在 listAvailableDrawCountBucketsForUpdate 里加了行锁，这里必然命中 1 行；0 行说明不变量被破坏
            if (drawCountDao.deductRemain(bucket.getBucketId()) == 0) {
                throw new IllegalStateException("扣减次数桶余量影响 0 行，桶余量与加锁读结果不一致 bucketId="
                        + bucket.getBucketId());
            }
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(result);
    }

    /** 当前可用桶（FIFO 加行锁），只服务 {@link #deductDrawCountForDraw} 的加锁读，不对外 */
    private List<DrawCountBucketEntity> listAvailableDrawCountBucketsForUpdate(String userId) {
        return drawCountDao.selectAvailableBucketsForUpdate(userId).stream().map(this::toDrawCountBucketEntity).toList();
    }

    @Override
    public int sumAvailableDrawCount(String userId) {
        return drawCountDao.sumAvailableRemain(userId);
    }

    @Override
    public int drawCountValidDays() {
        Integer validDays = drawCountConfigDao.selectDrawCountValidDays();
        if (validDays == null) {
            throw new IllegalStateException("draw_count_config 缺单行配置——种子或迁移没跑");
        }
        return validDays;
    }

    @Override
    public void updateDrawCountValidDays(int days) {
        if (drawCountConfigDao.updateDrawCountValidDays(days) == 0) {
            throw new IllegalStateException("draw_count_config 缺单行配置，更新无效——种子或迁移没跑");
        }
    }

    // ---------------------------------------------------------------- 购买单（ADR-0014，订单兼 outbox）

    @Override
    public boolean insertPurchaseOrder(DrawCountPurchaseOrderEntity order) {
        // REQUIRED：并入调用方（port 适配器）的组合事务，与积分扣分同提交同回滚
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                drawCountPurchaseOrderDao.insertOrder(toPurchaseOrderPO(order)) > 0));
    }

    @Override
    public DrawCountPurchaseOrderEntity queryPurchaseOrder(String purchaseOrderId) {
        return toPurchaseOrderEntity(drawCountPurchaseOrderDao.selectByPurchaseOrderId(purchaseOrderId));
    }

    @Override
    public List<DrawCountPurchaseOrderEntity> listDuePurchaseOrders(int limit) {
        return drawCountPurchaseOrderDao.listDue(limit).stream().map(this::toPurchaseOrderEntity).toList();
    }

    @Override
    public boolean claimPurchaseOrder(Long id, LocalDateTime nextRetryAt) {
        return drawCountPurchaseOrderDao.claim(id, nextRetryAt) > 0;
    }

    @Override
    public boolean finishPurchaseOrder(String purchaseOrderId) {
        return drawCountPurchaseOrderDao.finish(purchaseOrderId) > 0;
    }

    @Override
    public boolean markPurchaseOrderDead(Long id) {
        return drawCountPurchaseOrderDao.markDead(id) > 0;
    }

    private DrawCountPurchaseOrderPO toPurchaseOrderPO(DrawCountPurchaseOrderEntity order) {
        DrawCountPurchaseOrderPO po = new DrawCountPurchaseOrderPO();
        po.setPurchaseOrderId(order.getPurchaseOrderId());
        po.setUserId(order.getUserId());
        po.setQuantity(order.getQuantity());
        po.setPointsCost(order.getPointsCost());
        po.setState(order.getState().getCode());
        po.setRetryCount(order.getRetryCount());
        po.setNextRetryAt(order.getNextRetryAt());
        po.setFinishTime(order.getFinishTime());
        po.setCreateTime(order.getCreateTime());
        po.setUpdateTime(order.getUpdateTime());
        return po;
    }

    private DrawCountPurchaseOrderEntity toPurchaseOrderEntity(DrawCountPurchaseOrderPO po) {
        if (po == null) {
            return null;
        }
        DrawCountPurchaseOrderEntity order = new DrawCountPurchaseOrderEntity();
        order.setPurchaseOrderId(po.getPurchaseOrderId());
        order.setUserId(po.getUserId());
        order.setQuantity(po.getQuantity());
        order.setPointsCost(po.getPointsCost());
        order.setState(DrawCountPurchaseState.of(po.getState()));
        order.setRetryCount(po.getRetryCount());
        order.setNextRetryAt(po.getNextRetryAt());
        order.setFinishTime(po.getFinishTime());
        order.setCreateTime(po.getCreateTime());
        order.setUpdateTime(po.getUpdateTime());
        return order;
    }

    /** 插一行桶（{@link #creditDrawCountBucket} 的一步）；撞 uk_user_reason_ref 返回 false = 已记过 */
    private boolean insertDrawCountBucket(DrawCountBucketEntity bucket) {
        DrawCountBucketPO bucketPO = new DrawCountBucketPO();
        bucketPO.setBucketId(bucket.getBucketId());
        bucketPO.setUserId(bucket.getUserId());
        bucketPO.setAmount(bucket.getAmount());
        bucketPO.setRemain(bucket.getRemain());
        bucketPO.setExpireAt(bucket.getExpireAt());
        bucketPO.setReasonType(bucket.getReasonType().name());
        bucketPO.setRefId(bucket.getRefId());
        bucketPO.setCreateTime(bucket.getCreateTime());
        bucketPO.setUpdateTime(bucket.getUpdateTime());
        return drawCountDao.insertIgnoreBucket(bucketPO) > 0;
    }

    /**
     * 造一行待入账的桶：bucket_id 走统一 IdGenerator 发号（uuid-biz-id 票 07），
     * expire_at = 入账时点 + 次数有效期配置（都在事务内取）。
     */
    private DrawCountBucketEntity newDrawCountBucket(String userId, int amount, DrawCountReasonType reasonType,
                                                     String refId, LocalDateTime now) {
        DrawCountBucketEntity bucket = new DrawCountBucketEntity();
        bucket.setBucketId(idGenerator.nextId());
        bucket.setUserId(userId);
        bucket.setAmount(amount);
        bucket.setRemain(amount);
        bucket.setExpireAt(now.plusDays(drawCountValidDays()));
        bucket.setReasonType(reasonType);
        bucket.setRefId(refId);
        bucket.setCreateTime(now);
        bucket.setUpdateTime(now);
        return bucket;
    }

    private DrawCountBucketEntity toDrawCountBucketEntity(DrawCountBucketPO bucketPO) {
        DrawCountBucketEntity bucket = new DrawCountBucketEntity();
        bucket.setId(bucketPO.getId());
        bucket.setBucketId(bucketPO.getBucketId());
        bucket.setUserId(bucketPO.getUserId());
        bucket.setAmount(bucketPO.getAmount());
        bucket.setRemain(bucketPO.getRemain());
        bucket.setExpireAt(bucketPO.getExpireAt());
        bucket.setReasonType(DrawCountReasonType.valueOf(bucketPO.getReasonType()));
        bucket.setRefId(bucketPO.getRefId());
        bucket.setCreateTime(bucketPO.getCreateTime());
        bucket.setUpdateTime(bucketPO.getUpdateTime());
        return bucket;
    }

    // ---------------------------------------------------------------- Redis key 口径

    // key 口径的唯一定义在 types.raffle.Constants（本域与 points / inventory 各占一段前缀）
    private static final String RAFFLE_KEY_PREFIX = com.betterschool.marketing.types.raffle.Constants.RAFFLE_KEY_PREFIX;
    private static final String RAFFLE_BLACKLIST_USER_KEY = RAFFLE_KEY_PREFIX + "blacklist:user:";
    private static final String RAFFLE_AWARD_KEY = RAFFLE_KEY_PREFIX + "award:";
    private static final String RAFFLE_WARMUP_STATE_KEY = RAFFLE_KEY_PREFIX + "warmup:";

    // ---------------------------------------------------------------- PO / Entity 转换

    private RBucket<String> blacklistUserBucket(String strategyId, String userId) {
        return redissonClient.getBucket(RAFFLE_BLACKLIST_USER_KEY + strategyId + ":" + userId,
                StringCodec.INSTANCE);
    }

    private RBucket<String> awardBucket(String strategyId, String slot) {
        return redissonClient.getBucket(RAFFLE_AWARD_KEY + strategyId + ":" + slot,
                StringCodec.INSTANCE);
    }

    private DrawActivityEntity toActivityEntity(DrawActivityPO activityPO) {
        if (activityPO == null) {
            return null;
        }
        DrawActivityEntity entity = new DrawActivityEntity();
        entity.setId(activityPO.getId());
        entity.setActivityId(activityPO.getActivityId());
        entity.setActivityName(activityPO.getActivityName());
        entity.setStrategyId(activityPO.getStrategyId());
        entity.setBeginDateTime(activityPO.getBeginDateTime());
        entity.setEndDateTime(activityPO.getEndDateTime());
        entity.setState(activityPO.getState());
        entity.setCreateTime(activityPO.getCreateTime());
        entity.setUpdateTime(activityPO.getUpdateTime());
        return entity;
    }

    private DrawRecordEntity toRecordEntity(DrawRecordPO recordPO) {
        if (recordPO == null) {
            return null;
        }
        DrawRecordEntity entity = new DrawRecordEntity();
        entity.setId(recordPO.getId());
        entity.setRecordId(recordPO.getRecordId());
        entity.setUserId(recordPO.getUserId());
        entity.setActivityId(recordPO.getActivityId());
        entity.setStrategyId(recordPO.getStrategyId());
        entity.setAwardId(recordPO.getAwardId());
        entity.setAwardName(recordPO.getAwardName());
        entity.setAwardDesc(recordPO.getAwardDesc());
        entity.setAwardType(recordPO.getAwardType());
        entity.setLevel(recordPO.getLevel());
        entity.setInvId(recordPO.getInvId());
        entity.setState(recordPO.getState());
        entity.setCreateTime(recordPO.getCreateTime());
        entity.setUpdateTime(recordPO.getUpdateTime());
        return entity;
    }

    private DrawStrategyAwardEntity toAwardEntity(DrawStrategyAwardPO awardPO) {
        DrawStrategyAwardEntity entity = new DrawStrategyAwardEntity();
        entity.setId(awardPO.getId());
        entity.setStrategyId(awardPO.getStrategyId());
        entity.setAwardId(awardPO.getAwardId());
        entity.setAwardName(awardPO.getAwardName());
        entity.setAwardDesc(awardPO.getAwardDesc());
        entity.setAwardType(awardPO.getAwardType());
        entity.setAwardRate(awardPO.getAwardRate());
        entity.setLevel(awardPO.getLevel());
        entity.setInvId(awardPO.getInvId());
        entity.setType(awardPO.getType());
        entity.setSort(awardPO.getSort());
        entity.setCreateTime(awardPO.getCreateTime());
        entity.setUpdateTime(awardPO.getUpdateTime());
        return entity;
    }
}
