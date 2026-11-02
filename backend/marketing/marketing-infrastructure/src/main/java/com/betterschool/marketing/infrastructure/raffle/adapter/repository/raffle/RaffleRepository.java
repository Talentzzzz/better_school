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
import com.betterschool.marketing.infrastructure.raffle.dao.IRaffleDao;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawActivityPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordDetailPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawRecordPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyAwardPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.DrawStrategyWeightPO;
import com.betterschool.marketing.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 抽奖域唯一仓储实现：四张面（决策侧配置读 + 决策数据 Redis 读写、读侧翻页视图、落库写面、
 * 发货状态机写面）原样合成，全部走同一个 {@code IRaffleDao}；只有跨表 / 带 outbox 的写面用
 * {@code TransactionTemplate}（REQUIRED，可并入调用方事务）。
 */
@Repository
@RequiredArgsConstructor
public class RaffleRepository implements IRaffleRepository {

    private final IRaffleDao raffleDao;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final OutboxMessageStore outboxMessageStore;

    // ---------------------------------------------------------------- 配置读（决策侧）

    @Override
    public DrawActivityEntity queryActivityEntityByActivityId(String activityId) {
        return toActivityEntity(raffleDao.selectActivityByActivityId(activityId));
    }

    @Override
    public DrawStrategyEntity queryStrategyEntityByStrategyId(String strategyId) {
        DrawStrategyPO strategyPO = raffleDao.selectStrategyByStrategyId(strategyId);
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
        return raffleDao.selectAwardListByStrategyId(strategyId).stream()
                .map(this::toAwardEntity).toList();
    }

    @Override
    public List<DrawStrategyWeightEntity> queryWeightListByStrategyId(String strategyId) {
        return raffleDao.selectWeightListByStrategyId(strategyId).stream().map(weightPO -> {
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
        String awardId = raffleDao.selectAwardIdBySlot(strategyId, slot);
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
        return toActivityEntity(raffleDao.selectCurrentActivity());
    }

    @Override
    public DrawRecordEntity queryRecordByRecordId(String recordId) {
        return toRecordEntity(raffleDao.selectRecordByRecordId(recordId));
    }

    @Override
    public Integer queryPointsValueByInvId(String invId) {
        return raffleDao.selectPointsValueByInvId(invId);
    }

    @Override
    public int countTodayDrawn(String userId) {
        return raffleDao.countTodayDrawn(userId);
    }

    @Override
    public List<PrizeCatalogVO> queryPrizeCatalog(String strategyId) {
        return raffleDao.selectPrizeCatalog(strategyId).stream().map(po -> {
            PrizeCatalogVO vo = new PrizeCatalogVO();
            vo.setAwardId(po.getAwardId());
            vo.setAwardName(po.getAwardName());
            vo.setAwardType(po.getAwardType());
            vo.setLevel(po.getLevel());
            vo.setPointsAmount(po.getPointsAmount());
            vo.setRemaining(po.getRemaining());
            vo.setTotal(po.getTotal());
            vo.setUnlimited(po.getRemaining() == null && po.getTotal() == null);
            return vo;
        }).toList();
    }

    @Override
    public long countDrawRecords(String userId) {
        return raffleDao.countDrawRecords(userId);
    }

    @Override
    public List<DrawRecordVO> listDrawRecords(String userId, int offset, int limit) {
        return raffleDao.selectDrawRecordViews(userId, offset, limit).stream().map(po ->
                new DrawRecordVO(po.getRecordId(), po.getAwardId(), po.getAwardName(), po.getAwardType(),
                        po.getLevel(), po.getPointsAmount(), po.getCreateTime())).toList();
    }

    @Override
    public long countMyPrizes(String userId, List<String> states) {
        return raffleDao.countMyPrizes(userId, states);
    }

    @Override
    public List<UserPrizeVO> listMyPrizes(String userId, List<String> states, int offset, int limit) {
        return raffleDao.selectMyPrizeViews(userId, states, offset, limit).stream().map(po ->
                new UserPrizeVO(po.getRecordId(), po.getAwardId(), po.getAwardName(), po.getAwardType(),
                        po.getLevel(), po.getState(), po.getCreateTime(), po.getClaimedAt())).toList();
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
            raffleDao.insertIgnoreRecordDetail(detailPO);

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
                raffleDao.insertIgnoreRecordDetail(detailPO);
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
