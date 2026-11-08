package com.betterschool.marketing.infrastructure.raffle.adapter.repository.raffle;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.aggregate.DrawRecordAggregate;
import com.betterschool.marketing.domain.raffle.model.entity.DrawActivityEntity;
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
import com.betterschool.marketing.infrastructure.inventory.dao.IPhysicalQuotaDao;
import com.betterschool.marketing.infrastructure.inventory.dao.IVirtualInventoryQuotaDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawActivityDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawRecordDetailDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawStrategyAwardDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawStrategyDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IDrawStrategyWeightDao;
import com.betterschool.marketing.infrastructure.raffle.dao.IRaffleDao;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawActivityPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordDetailPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordViewPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyAwardPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyWeightPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawUserPrizeViewPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.ExchangeDetailRowPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.PhysicalQuotaRowPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.VirtualQuotaRowPO;
import com.betterschool.marketing.types.common.Constants;
import lombok.RequiredArgsConstructor;
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
 * 抽奖域唯一仓储实现：四张面（决策侧配置读 + 决策数据 Redis 读写、读侧翻页视图、落库写面、
 * 发货状态机写面）原样合成，数据访问按表分落各 DAO（一表一 DAO；台账批量回查直接注入
 * 库存域的 {@code IVirtualInventoryQuotaDao} / {@code IPhysicalQuotaDao}）；只有跨表 /
 * 带 outbox 的写面用 {@code TransactionTemplate}（REQUIRED，可并入调用方事务）。
 */
@Repository
@RequiredArgsConstructor
public class RaffleRepository implements IRaffleRepository {

    private final IRaffleDao raffleDao;
    private final IDrawActivityDao drawActivityDao;
    private final IDrawStrategyDao drawStrategyDao;
    private final IDrawStrategyAwardDao drawStrategyAwardDao;
    private final IDrawStrategyWeightDao drawStrategyWeightDao;
    private final IDrawRecordDetailDao drawRecordDetailDao;
    private final IVirtualInventoryQuotaDao virtualInventoryQuotaDao;
    private final IPhysicalQuotaDao physicalQuotaDao;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final OutboxMessageStore outboxMessageStore;

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
    public Integer queryPointsValueByInvId(String invId) {
        return virtualInventoryQuotaDao.selectPointsValueByInvId(invId);
    }

    @Override
    public int countTodayDrawn(String userId) {
        return raffleDao.countTodayDrawn(userId);
    }

    @Override
    public List<PrizeCatalogVO> queryPrizeCatalog(String strategyId) {
        // 三段查拼装（原奖品行 LEFT JOIN 两段台账一步投影，SQL 层零 JOIN）：①奖品行（同 sort,id 排序）；
        // ②虚拟台账 ③实物台账按 inv_id 集批量回查；CASE 语义在 Java 收口——
        // remaining = 台账 surplus_qty、total = 虚拟 surplus+issued / 实物 surplus+waiting+issued，
        // inv_id='0'（不限量哨兵）或 surplus < 0 落 null 由 unlimited 表达；台账行缺失 = 原 LEFT JOIN 空行，同样落 null
        List<DrawStrategyAwardPO> awards = drawStrategyAwardDao.selectAwardListByStrategyId(strategyId);
        if (awards.isEmpty()) {
            return List.of();
        }
        List<String> invIds = awards.stream().map(DrawStrategyAwardPO::getInvId)
                .filter(invId -> invId != null && !"0".equals(invId))
                .distinct().toList();
        Map<String, VirtualQuotaRowPO> virtualByInv = invIds.isEmpty() ? Map.of()
                : virtualInventoryQuotaDao.selectVirtualQuotasByInvIds(invIds).stream()
                        .collect(Collectors.toMap(VirtualQuotaRowPO::getInvId, row -> row, (a, b) -> a));
        Map<String, PhysicalQuotaRowPO> physicalByInv = invIds.isEmpty() ? Map.of()
                : physicalQuotaDao.selectPhysicalQuotasByInvIds(invIds).stream()
                        .collect(Collectors.toMap(PhysicalQuotaRowPO::getInvId, row -> row, (a, b) -> a));
        List<PrizeCatalogVO> prizes = new ArrayList<>(awards.size());
        for (DrawStrategyAwardPO award : awards) {
            PrizeCatalogVO vo = new PrizeCatalogVO();
            vo.setAwardId(award.getAwardId());
            vo.setAwardName(award.getAwardName());
            vo.setAwardType(award.getAwardType());
            vo.setLevel(award.getLevel());
            VirtualQuotaRowPO virtual = virtualByInv.get(award.getInvId());
            // 积分档面额（VIRTUAL + 库存 type=POINTS），其余为 null
            vo.setPointsAmount("VIRTUAL".equals(award.getAwardType())
                    && virtual != null && "POINTS".equals(virtual.getType()) ? virtual.getValue() : null);
            Long remaining = null;
            Long total = null;
            if (virtual != null) {
                remaining = virtual.getSurplusQty() < 0 ? null : virtual.getSurplusQty();
                total = virtual.getSurplusQty() < 0 ? null : virtual.getSurplusQty() + virtual.getIssuedQty();
            } else if (physicalByInv.get(award.getInvId()) != null) {
                PhysicalQuotaRowPO physical = physicalByInv.get(award.getInvId());
                remaining = physical.getSurplusQty() < 0 ? null : physical.getSurplusQty();
                total = physical.getSurplusQty() < 0 ? null
                        : physical.getSurplusQty() + physical.getWaitingQty() + physical.getIssuedQty();
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
        // 两段查拼装（原 draw_record LEFT JOIN 积分台账，SQL 层零 JOIN）：记录行单表分页 + 按 inv_id 集
        // 批量回查 type=POINTS 面额；缺行 = null（原 LEFT JOIN 空行口径）
        List<DrawRecordViewPO> rows = raffleDao.selectDrawRecordRows(userId, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> invIds = rows.stream().map(DrawRecordViewPO::getInvId)
                .filter(invId -> invId != null && !"0".equals(invId))
                .distinct().toList();
        Map<String, Integer> pointValues = invIds.isEmpty() ? Map.of()
                : virtualInventoryQuotaDao.selectPointValuesByInvIds(invIds).stream()
                        .filter(row -> row.getValue() != null)
                        .collect(Collectors.toMap(VirtualQuotaRowPO::getInvId, VirtualQuotaRowPO::getValue, (a, b) -> a));
        return rows.stream().map(po -> new DrawRecordVO(po.getRecordId(), po.getAwardId(), po.getAwardName(),
                po.getAwardType(), po.getLevel(), pointValues.get(po.getInvId()), po.getCreateTime())).toList();
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
