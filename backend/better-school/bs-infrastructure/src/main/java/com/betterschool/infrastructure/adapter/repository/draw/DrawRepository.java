package com.betterschool.infrastructure.adapter.repository.draw;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.aggregate.DrawRecordAggregate;
import com.betterschool.domain.draw.model.entity.DrawActivityEntity;
import com.betterschool.domain.draw.model.entity.DrawCountBucketEntity;
import com.betterschool.domain.draw.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.domain.draw.model.entity.DrawOrphanPrereserveOrderEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordDetailEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.domain.draw.model.entity.DrawReconRoundEntity;
import com.betterschool.domain.draw.model.entity.DrawReconVarianceEntity;
import com.betterschool.domain.draw.model.entity.DrawStrategyAwardEntity;
import com.betterschool.domain.draw.model.entity.DrawStrategyEntity;
import com.betterschool.domain.draw.model.entity.DrawStrategyWeightEntity;
import com.betterschool.domain.draw.model.valobj.DrawRecordActionEnum;
import com.betterschool.domain.draw.model.valobj.DrawRecordVO;
import com.betterschool.domain.draw.model.valobj.PrizeCatalogVO;
import com.betterschool.domain.draw.model.valobj.UserPrizeVO;
import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.betterschool.infrastructure.dao.award.IAwardInventoryDao;
import com.betterschool.infrastructure.dao.award.po.AwardInventoryPO;
import com.betterschool.infrastructure.dao.draw.IDrawActivityDao;
import com.betterschool.infrastructure.dao.draw.IDrawCountConfigDao;
import com.betterschool.infrastructure.dao.draw.IDrawCountDao;
import com.betterschool.infrastructure.dao.draw.IDrawCountDetailDao;
import com.betterschool.infrastructure.dao.draw.IDrawCountPurchaseOrderDao;
import com.betterschool.infrastructure.dao.draw.IDrawOrphanPrereserveOrderDao;
import com.betterschool.infrastructure.dao.draw.IDrawReconRoundDao;
import com.betterschool.infrastructure.dao.draw.IDrawReconVarianceDao;
import com.betterschool.infrastructure.dao.draw.IDrawRecordDetailDao;
import com.betterschool.infrastructure.dao.draw.IDrawStrategyAwardDao;
import com.betterschool.infrastructure.dao.draw.IDrawStrategyDao;
import com.betterschool.infrastructure.dao.draw.IDrawStrategyWeightDao;
import com.betterschool.infrastructure.dao.draw.IDrawDao;
import com.betterschool.infrastructure.dao.draw.po.DrawActivityPO;
import com.betterschool.infrastructure.dao.draw.po.DrawCountBucketPO;
import com.betterschool.infrastructure.dao.draw.po.DrawCountDetailPO;
import com.betterschool.infrastructure.dao.draw.po.DrawCountPurchaseOrderPO;
import com.betterschool.infrastructure.dao.draw.po.DrawOrphanPrereserveOrderPO;
import com.betterschool.infrastructure.dao.draw.po.DrawReconRoundPO;
import com.betterschool.infrastructure.dao.draw.po.DrawReconVariancePO;
import com.betterschool.infrastructure.dao.draw.po.DrawRecordDetailPO;
import com.betterschool.infrastructure.dao.draw.po.DrawRecordPO;
import com.betterschool.infrastructure.dao.draw.po.DrawRecordViewPO;
import com.betterschool.infrastructure.dao.draw.po.DrawStrategyAwardPO;
import com.betterschool.infrastructure.dao.draw.po.DrawStrategyPO;
import com.betterschool.infrastructure.dao.draw.po.DrawUserPrizeViewPO;
import com.betterschool.infrastructure.dao.draw.po.ExchangeDetailRowPO;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.DrawReconAlertMessage;
import com.betterschool.types.id.IIdGenerator;
import com.betterschool.types.draw.DrawCountPurchaseStateEnum;
import com.betterschool.types.draw.DrawCountReasonTypeEnum;
import com.betterschool.types.draw.DrawOrphanPrereserveStateEnum;
import com.betterschool.types.draw.DrawReconCheckTypeEnum;
import com.betterschool.types.draw.DrawReconVarianceStateEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * 直接注入奖品域的 {@code IAwardInventoryDao}）；跨表 /
 * 带 outbox / 桶与明细的写面用 {@code TransactionTemplate}（REQUIRED，可并入调用方事务）。
 * <p>
 * 次数桶四张表（bucket / detail / config）随「抽奖次数与抽奖同域」并入本仓储，原
 * {@code DrawCountRepository} 的方法体原样搬入（入账、扣次两处事务仍由本类承担）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DrawRepository implements IDrawRepository {

    private final IDrawDao drawDao;
    private final IDrawActivityDao drawActivityDao;
    private final IDrawStrategyDao drawStrategyDao;
    private final IDrawStrategyAwardDao drawStrategyAwardDao;
    private final IDrawStrategyWeightDao drawStrategyWeightDao;
    private final IDrawRecordDetailDao drawRecordDetailDao;
    private final IDrawCountDao drawCountDao;
    private final IDrawCountDetailDao drawCountDetailDao;
    private final IDrawCountPurchaseOrderDao drawCountPurchaseOrderDao;
    private final IDrawOrphanPrereserveOrderDao drawOrphanPrereserveOrderDao;
    private final IDrawCountConfigDao drawCountConfigDao;
    private final IDrawReconVarianceDao drawReconVarianceDao;
    private final IDrawReconRoundDao drawReconRoundDao;
    private final IAwardInventoryDao awardInventoryDao;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final IIdGenerator idGenerator;

    // ---------------------------------------------------------------- 配置读（决策侧）

    @Override
    public DrawActivityEntity queryActivityEntityByActivityId(String activityId) {
        return toActivityEntity(drawActivityDao.selectActivityByActivityId(activityId));
    }

    @Override
    public DrawStrategyEntity queryStrategyEntityByStrategyId(String strategyId) {
        DrawStrategyPO drawStrategyPO = drawStrategyDao.selectStrategyByStrategyId(strategyId);
        if (drawStrategyPO == null) {
            return null;
        }
        DrawStrategyEntity drawStrategyEntity = new DrawStrategyEntity();
        drawStrategyEntity.setId(drawStrategyPO.getId());
        drawStrategyEntity.setStrategyId(drawStrategyPO.getStrategyId());
        drawStrategyEntity.setStrategyDesc(drawStrategyPO.getStrategyDesc());
        if (drawStrategyPO.getRule() != null && !drawStrategyPO.getRule().isBlank()) {
            drawStrategyEntity.setRuleModels(Arrays.stream(drawStrategyPO.getRule().split(","))
                    .map(String::trim).filter(segment -> !segment.isEmpty()).toList());
        } else {
            drawStrategyEntity.setRuleModels(Collections.emptyList());
        }
        drawStrategyEntity.setCreateTime(drawStrategyPO.getCreateTime());
        drawStrategyEntity.setUpdateTime(drawStrategyPO.getUpdateTime());
        return drawStrategyEntity;
    }

    @Override
    public List<DrawStrategyAwardEntity> queryAwardListByStrategyId(String strategyId) {
        return drawStrategyAwardDao.selectAwardListByStrategyId(strategyId).stream()
                .map(this::toAwardEntity).toList();
    }

    @Override
    public List<DrawStrategyWeightEntity> queryWeightListByStrategyId(String strategyId) {
        return drawStrategyWeightDao.selectWeightListByStrategyId(strategyId).stream().map(weightPO -> {
            DrawStrategyWeightEntity drawStrategyWeightEntity = new DrawStrategyWeightEntity();
            drawStrategyWeightEntity.setId(weightPO.getId());
            drawStrategyWeightEntity.setStrategyId(weightPO.getStrategyId());
            drawStrategyWeightEntity.setCount(weightPO.getCount());
            drawStrategyWeightEntity.setWeights(Arrays.stream(weightPO.getWeights().split(","))
                    .map(String::trim).filter(segment -> !segment.isEmpty())
                    .toList());
            drawStrategyWeightEntity.setCreateTime(weightPO.getCreateTime());
            drawStrategyWeightEntity.setUpdateTime(weightPO.getUpdateTime());
            return drawStrategyWeightEntity;
        }).toList();
    }

    @Override
    public int queryUserDrawCount(String strategyId, String userId) {
        return drawDao.countUserRecords(strategyId, userId);
    }

    // ---------------------------------------------------------------- 决策数据（Redis）

    @Override
    public void cacheDrawTable(String key, Map<Integer, String> table) {
        redissonClient.getMap(key).delete();
        RMap<Integer, String> drawTable = redissonClient.getMap(key);
        drawTable.putAll(table);
    }

    @Override
    public Map<Integer, String> getDrawTable(String key) {
        RMap<Integer, String> drawTable = redissonClient.getMap(key);
        return drawTable.isEmpty() ? Collections.emptyMap() : drawTable.readAllMap();
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
        redissonClient.getBucket(DRAW_WARMUP_STATE_KEY + strategyId, StringCodec.INSTANCE)
                .set(LocalDateTime.now().toString());
    }

    @Override
    public boolean isWarmed(String strategyId) {
        return redissonClient.getBucket(DRAW_WARMUP_STATE_KEY + strategyId, StringCodec.INSTANCE)
                .isExists();
    }

    // ---------------------------------------------------------------- 读侧

    @Override
    public DrawActivityEntity queryCurrentActivity() {
        return toActivityEntity(drawActivityDao.selectCurrentActivity());
    }

    @Override
    public DrawRecordEntity queryRecordByRecordId(String recordId) {
        return toRecordEntity(drawDao.selectRecordByRecordId(recordId));
    }

    @Override
    public Integer queryPointsValueByAwardId(String awardId) {
        // 面额真相在奖品库存账户的 value_qty（虚拟奖品单次发放面额）
        AwardInventoryPO awardInventoryPO = awardInventoryDao.selectByAwardId(awardId);
        return awardInventoryPO == null || awardInventoryPO.getValueQty() == null
                ? null : awardInventoryPO.getValueQty().intValue();
    }

    @Override
    public boolean existsAward(String awardId) {
        return drawStrategyAwardDao.existsByAwardId(awardId);
    }

    @Override
    public int countTodayDrawn(String userId) {
        return drawDao.countTodayDrawn(userId);
    }

    @Override
    public List<PrizeCatalogVO> queryPrizeCatalog(String strategyId) {
        // 两段查拼装（SQL 层零 JOIN）：①奖品行（同 sort,id 排序）；②奖品库存账户按 award_id 集批量回查
        // （remaining/total 与虚拟面额的真相）。CASE 语义在 Java 收口——
        // remaining = 账户 surplus_qty、total = 虚拟 surplus+issued / 实物 surplus+waiting+issued，
        // surplus < 0（不限量）或账户行缺失（未投放）落 null 由 unlimited 表达
        List<DrawStrategyAwardPO> awards = drawStrategyAwardDao.selectAwardListByStrategyId(strategyId);
        if (awards.isEmpty()) {
            return List.of();
        }
        List<String> awardIds = awards.stream().map(DrawStrategyAwardPO::getAwardId).distinct().toList();
        Map<String, AwardInventoryPO> inventoryByAward = awardInventoryDao.selectByAwardIds(awardIds).stream()
                .collect(Collectors.toMap(AwardInventoryPO::getAwardId, row -> row, (a, b) -> a));
        List<PrizeCatalogVO> prizes = new ArrayList<>(awards.size());
        for (DrawStrategyAwardPO drawStrategyAwardPO : awards) {
            PrizeCatalogVO prizeCatalogVO = new PrizeCatalogVO();
            prizeCatalogVO.setAwardId(drawStrategyAwardPO.getAwardId());
            prizeCatalogVO.setAwardName(drawStrategyAwardPO.getAwardName());
            prizeCatalogVO.setAwardType(drawStrategyAwardPO.getAwardType());
            prizeCatalogVO.setLevel(drawStrategyAwardPO.getLevel());
            AwardInventoryPO awardInventoryPO = inventoryByAward.get(drawStrategyAwardPO.getAwardId());
            // 虚拟档面额取账户 value_qty；其余为 null
            prizeCatalogVO.setPointsAmount("VIRTUAL".equals(drawStrategyAwardPO.getAwardType()) && awardInventoryPO != null
                    && awardInventoryPO.getValueQty() != null ? awardInventoryPO.getValueQty().intValue() : null);
            Long remaining = null;
            Long total = null;
            if (awardInventoryPO != null && awardInventoryPO.getSurplusQty() >= 0) {
                remaining = awardInventoryPO.getSurplusQty();
                total = "PHYSICAL".equals(awardInventoryPO.getType())
                        ? awardInventoryPO.getSurplusQty() + awardInventoryPO.getWaitingQty() + awardInventoryPO.getIssuedQty()
                        : awardInventoryPO.getSurplusQty() + awardInventoryPO.getIssuedQty();
            }
            prizeCatalogVO.setRemaining(remaining);
            prizeCatalogVO.setTotal(total);
            prizeCatalogVO.setUnlimited(remaining == null && total == null);
            prizes.add(prizeCatalogVO);
        }
        return prizes;
    }

    @Override
    public long countDrawRecords(String userId) {
        return drawDao.countDrawRecords(userId);
    }

    @Override
    public List<DrawRecordVO> listDrawRecords(String userId, int offset, int limit) {
        // 两段查拼装（SQL 层零 JOIN）：记录行单表分页 + 按 award_id（draw_record.inv_id 快照 =
        // 奖品库存账户维度）集回查奖项品库存账户的 value_qty；缺行 = null（原 LEFT JOIN 空行口径）
        List<DrawRecordViewPO> rows = drawDao.selectDrawRecordRows(userId, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> awardIds = rows.stream().map(DrawRecordViewPO::getInvId)
                .filter(invId -> invId != null)
                .distinct().toList();
        Map<String, Integer> pointValues = awardIds.isEmpty() ? Map.of()
                : awardInventoryDao.selectByAwardIds(awardIds).stream()
                        .filter(row -> row.getValueQty() != null)
                        .collect(Collectors.toMap(AwardInventoryPO::getAwardId,
                                row -> row.getValueQty().intValue(), (a, b) -> a));
        return rows.stream().map(po -> new DrawRecordVO(po.getRecordId(), po.getAwardId(), po.getAwardName(),
                        po.getAwardType(), po.getLevel(),
                        po.getInvId() == null ? null : pointValues.get(po.getInvId()),
                        po.getCreateTime())).toList();
    }

    @Override
    public long countMyPrizes(String userId, List<String> states) {
        return drawDao.countMyPrizes(userId, states);
    }

    @Override
    public List<UserPrizeVO> listMyPrizes(String userId, List<String> states, int offset, int limit) {
        // 两段查拼装（原 draw_record LEFT JOIN EXCHANGE 明细，SQL 层零 JOIN）：记录行单表分页 +
        // 按 record_id 集批量回查兑换明细；缺行 = 还没兑换，claimedAt 落 null（原 LEFT JOIN 口径）
        List<DrawUserPrizeViewPO> rows = drawDao.selectMyPrizeRows(userId, states, offset, limit);
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
    public boolean insertDrawRecord(DrawRecordAggregate drawRecordAggregate) {
        List<MqOutbox> pendingOutboxList = new ArrayList<>(1);
        Boolean inserted = transactionTemplate.execute(status -> {
            DrawRecordPO drawRecordPO = new DrawRecordPO();
            drawRecordPO.setRecordId(drawRecordAggregate.getRecord().getRecordId());
            drawRecordPO.setUserId(drawRecordAggregate.getRecord().getUserId());
            drawRecordPO.setActivityId(drawRecordAggregate.getRecord().getActivityId());
            drawRecordPO.setStrategyId(drawRecordAggregate.getRecord().getStrategyId());
            drawRecordPO.setAwardId(drawRecordAggregate.getRecord().getAwardId());
            drawRecordPO.setAwardName(drawRecordAggregate.getRecord().getAwardName());
            drawRecordPO.setAwardDesc(drawRecordAggregate.getRecord().getAwardDesc());
            drawRecordPO.setAwardType(drawRecordAggregate.getRecord().getAwardType());
            drawRecordPO.setLevel(drawRecordAggregate.getRecord().getLevel());
            drawRecordPO.setInvId(drawRecordAggregate.getRecord().getInvId());
            drawRecordPO.setState(drawRecordAggregate.getRecord().getState());
            drawRecordPO.setCreateTime(drawRecordAggregate.getRecord().getCreateTime());
            drawRecordPO.setUpdateTime(drawRecordAggregate.getRecord().getUpdateTime());
            drawDao.insertDrawRecord(drawRecordPO);

            DrawRecordDetailPO drawRecordDetailPO = new DrawRecordDetailPO();
            drawRecordDetailPO.setUserId(drawRecordAggregate.getDetail().getUserId());
            drawRecordDetailPO.setRecordId(drawRecordAggregate.getDetail().getRecordId());
            drawRecordDetailPO.setType(drawRecordAggregate.getDetail().getType());
            drawRecordDetailPO.setCreateTime(drawRecordAggregate.getDetail().getCreateTime());
            drawRecordDetailPO.setUpdateTime(drawRecordAggregate.getDetail().getUpdateTime());
            // detail 撞 uk_record_type = 该记录 DRAW 已落过（重试重放），忽略；
            // record 撞 uk_record_id 抛 DuplicateKeyException，重放判定归调用方编排层
            drawRecordDetailDao.insertIgnoreRecordDetail(drawRecordDetailPO);

            // 中奖事件：THANKS 档不带事件（谢谢参与不发），其余与业务写同事务落 outbox
            if (drawRecordAggregate.getDrawnEvent() != null) {
                pendingOutboxList.add(saveOutbox(Constants.LOTTERY_DRAWN_TOPIC, drawRecordAggregate.getDrawnEvent()));
            }
            return true;
        });
        // 事务已提交：事务外同步发送并更新 outbox 状态，失败留 PENDING 交 outboxScan 兜底
        sendOutboxes(pendingOutboxList);
        return Boolean.TRUE.equals(inserted);
    }

    /** 事务内只做 DAO 写：落一行 PENDING outbox，发送交给事务提交后的调用点 */
    private MqOutbox saveOutbox(String destination, Object payload) {
        MqOutbox mqOutbox = MqOutbox.pending(destination, payload, objectMapper);
        mqOutboxDao.insert(mqOutbox);
        return mqOutbox;
    }

    /**
     * 事务外逐条同步发送并更新 outbox 状态。仓储事务是 REQUIRED——若并入调用方事务（执行后事务仍活跃），
     * 不在提交前发送，留给 outboxScan 兜底，避免「消息已发、外层事务回滚」。
     */
    private void sendOutboxes(List<MqOutbox> outboxList) {
        if (outboxList.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.debug("[DrawRepository] outbox 并入调用方事务，留给 outboxScan 兜底 msgIds={}",
                    outboxList.stream().map(MqOutbox::getMsgId).toList());
            return;
        }
        for (MqOutbox mqOutbox : outboxList) {
            sendOutbox(mqOutbox);
        }
    }

    private void sendOutbox(MqOutbox mqOutbox) {
        boolean sent;
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(mqOutbox.getDestination(), mqOutbox.getPayload());
            sent = sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
            if (!sent) {
                log.warn("outbox 同步发送未确认 msgId={}, status={}", mqOutbox.getMsgId(),
                        sendResult == null ? null : sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.warn("outbox 同步发送失败 msgId={}", mqOutbox.getMsgId(), e);
            sent = false;
        }
        if (sent) {
            mqOutboxDao.markSentByMsgId(mqOutbox.getMsgId());
        } else if (mqOutbox.getRetryCount() + 1 >= MqOutbox.MAX_RETRY_COUNT) {
            mqOutboxDao.markDeadBatch(List.of(mqOutbox.getId()));
        } else {
            mqOutboxDao.incrementRetryBatch(List.of(mqOutbox.getId()));
        }
    }

    // ---------------------------------------------------------------- 发货状态机写面

    @Override
    public boolean advanceState(String userId, String recordId, String fromState,
                                List<DrawRecordDetailEntity> details) {
        Boolean advanced = transactionTemplate.execute(status -> {
            String currentState = fromState;
            for (DrawRecordDetailEntity drawRecordDetailEntity : details) {
                String toState = DrawRecordActionEnum.fromCode(drawRecordDetailEntity.getType()).getTargetState();
                if (drawDao.updateRecordState(recordId, currentState, toState) == 0) {
                    // 起点不匹配：重复投递 / 已终态 → 整笔回滚（前一步的推进与明细一起消失）
                    status.setRollbackOnly();
                    return false;
                }
                DrawRecordDetailPO drawRecordDetailPO = new DrawRecordDetailPO();
                drawRecordDetailPO.setUserId(userId);
                drawRecordDetailPO.setRecordId(recordId);
                drawRecordDetailPO.setType(drawRecordDetailEntity.getType());
                drawRecordDetailPO.setCreateTime(drawRecordDetailEntity.getCreateTime());
                drawRecordDetailPO.setUpdateTime(drawRecordDetailEntity.getUpdateTime());
                drawRecordDetailDao.insertIgnoreRecordDetail(drawRecordDetailPO);
                currentState = toState;
            }
            return true;
        });
        return Boolean.TRUE.equals(advanced);
    }

    // ---------------------------------------------------------------- 抽奖次数桶账务（同域）

    @Override
    public boolean addDrawCountBucket(String userId, int amount, DrawCountReasonTypeEnum drawCountReasonType, String refId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                insertDrawCountBucket(newDrawCountBucket(userId, amount, drawCountReasonType, refId, LocalDateTime.now()))));
    }

    @Override
    public boolean deductDrawCountForDraw(String userId, String recordId) {
        Boolean result = transactionTemplate.execute(status -> {
            if (drawCountDetailDao.selectDetailIdForUpdate(recordId) != null) {
                // 同一抽奖记录重放：幂等跳过，不重复扣次
                log.info("[DrawRepository] 次数扣次明细已存在，幂等跳过 userId={}, recordId={}", userId, recordId);
                return Boolean.TRUE;
            }
            List<DrawCountBucketEntity> buckets = listAvailableDrawCountBucketsForUpdate(userId);
            if (buckets.isEmpty()) {
                // 一行都没动，事务空提交——不用回滚来表达业务失败
                return Boolean.FALSE;
            }
            // 每次抽奖恒扣 1 次：最早的可用桶必有 remain >= 1，单个桶即扣完，不跨桶
            DrawCountBucketEntity drawCountBucketEntity = buckets.get(0);
            LocalDateTime now = LocalDateTime.now();
            DrawCountDetailPO drawCountDetailPO = new DrawCountDetailPO();
            drawCountDetailPO.setUserId(userId);
            drawCountDetailPO.setBucketId(drawCountBucketEntity.getBucketId());
            drawCountDetailPO.setRefId(recordId);
            drawCountDetailPO.setCreateTime(now);
            drawCountDetailPO.setUpdateTime(now);
            // 流水先行：INSERT IGNORE 明细撞 uk = 这笔抽奖已被并发事务扣过 ⇒ 整事务回滚
            if (drawCountDetailDao.insertIgnoreDetail(drawCountDetailPO) == 0) {
                log.warn("[DrawRepository] 扣次明细撞唯一键，判定为重复抽奖 userId={}, bucketId={}, recordId={}",
                        userId, drawCountBucketEntity.getBucketId(), recordId);
                status.setRollbackOnly();
                return null;
            }
            // 桶已在 listAvailableDrawCountBucketsForUpdate 里加了行锁，这里必然命中 1 行；0 行说明不变量被破坏
            if (drawCountDao.deductRemain(drawCountBucketEntity.getBucketId()) == 0) {
                throw new IllegalStateException("扣减次数桶余量影响 0 行，桶余量与加锁读结果不一致 bucketId="
                        + drawCountBucketEntity.getBucketId());
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
    public boolean insertPurchaseOrder(DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity) {
        // 第一段的本地事务（ADR-0016 票 C）：购买单 INSERT 自成一个 draw 仓储事务，
        // 不再有调用方（port 组合事务）可并入
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                drawCountPurchaseOrderDao.insertOrder(toPurchaseOrderPO(drawCountPurchaseOrderEntity)) > 0));
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
    public boolean markPurchaseOrderDead(String purchaseOrderId) {
        return drawCountPurchaseOrderDao.markDead(purchaseOrderId) > 0;
    }

    // ---------------------------------------------------------------- 孤儿预扣对账单（扫描种单 + 重试状态机）

    @Override
    public boolean insertOrphanPrereserveOrder(DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity) {
        // 种单是扫描动作：INSERT IGNORE 撞 uk_record_id 静默跳过（DONE/DEAD 的对账单不再被重新种）
        return drawOrphanPrereserveOrderDao.insertIgnoreOrder(toOrphanPrereserveOrderPO(drawOrphanPrereserveOrderEntity)) > 0;
    }

    @Override
    public List<DrawOrphanPrereserveOrderEntity> listDueOrphanPrereserveOrders(int limit) {
        return drawOrphanPrereserveOrderDao.listDue(limit).stream()
                .map(this::toOrphanPrereserveOrderEntity).toList();
    }

    @Override
    public boolean claimOrphanPrereserveOrder(Long id, LocalDateTime nextRetryAt) {
        return drawOrphanPrereserveOrderDao.claim(id, nextRetryAt) > 0;
    }

    @Override
    public boolean finishOrphanPrereserveOrder(String recordId) {
        return drawOrphanPrereserveOrderDao.finish(recordId) > 0;
    }

    @Override
    public boolean markOrphanPrereserveOrderDead(Long id) {
        return drawOrphanPrereserveOrderDao.markDead(id) > 0;
    }

    @Override
    public String queryUserIdByDrawCountRef(String refId) {
        DrawCountDetailPO drawCountDetailPO = drawCountDetailDao.selectByRefId(refId);
        return drawCountDetailPO == null ? null : drawCountDetailPO.getUserId();
    }

    @Override
    public DrawOrphanPrereserveOrderEntity queryOrphanPrereserveOrderByRecordId(String recordId) {
        return toOrphanPrereserveOrderEntity(drawOrphanPrereserveOrderDao.selectByRecordId(recordId));
    }

    @Override
    public List<DrawOrphanPrereserveOrderEntity> listDeadOrphanPrereserveOrders(int limit) {
        return drawOrphanPrereserveOrderDao.listDead(limit).stream()
                .map(this::toOrphanPrereserveOrderEntity).toList();
    }

    private DrawOrphanPrereserveOrderPO toOrphanPrereserveOrderPO(DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity) {
        DrawOrphanPrereserveOrderPO drawOrphanPrereserveOrderPO = new DrawOrphanPrereserveOrderPO();
        drawOrphanPrereserveOrderPO.setRecordId(drawOrphanPrereserveOrderEntity.getRecordId());
        drawOrphanPrereserveOrderPO.setAwardId(drawOrphanPrereserveOrderEntity.getAwardId());
        drawOrphanPrereserveOrderPO.setUserId(drawOrphanPrereserveOrderEntity.getUserId());
        drawOrphanPrereserveOrderPO.setQuantity(drawOrphanPrereserveOrderEntity.getQuantity());
        drawOrphanPrereserveOrderPO.setState(drawOrphanPrereserveOrderEntity.getState().getCode());
        drawOrphanPrereserveOrderPO.setRetryCount(drawOrphanPrereserveOrderEntity.getRetryCount());
        drawOrphanPrereserveOrderPO.setNextRetryAt(drawOrphanPrereserveOrderEntity.getNextRetryAt());
        drawOrphanPrereserveOrderPO.setFinishTime(drawOrphanPrereserveOrderEntity.getFinishTime());
        drawOrphanPrereserveOrderPO.setCreateTime(drawOrphanPrereserveOrderEntity.getCreateTime());
        drawOrphanPrereserveOrderPO.setUpdateTime(drawOrphanPrereserveOrderEntity.getUpdateTime());
        return drawOrphanPrereserveOrderPO;
    }

    private DrawOrphanPrereserveOrderEntity toOrphanPrereserveOrderEntity(DrawOrphanPrereserveOrderPO drawOrphanPrereserveOrderPO) {
        if (drawOrphanPrereserveOrderPO == null) {
            return null;
        }
        DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity = new DrawOrphanPrereserveOrderEntity();
        drawOrphanPrereserveOrderEntity.setId(drawOrphanPrereserveOrderPO.getId());
        drawOrphanPrereserveOrderEntity.setRecordId(drawOrphanPrereserveOrderPO.getRecordId());
        drawOrphanPrereserveOrderEntity.setAwardId(drawOrphanPrereserveOrderPO.getAwardId());
        drawOrphanPrereserveOrderEntity.setUserId(drawOrphanPrereserveOrderPO.getUserId());
        drawOrphanPrereserveOrderEntity.setQuantity(drawOrphanPrereserveOrderPO.getQuantity());
        drawOrphanPrereserveOrderEntity.setState(DrawOrphanPrereserveStateEnum.of(drawOrphanPrereserveOrderPO.getState()));
        drawOrphanPrereserveOrderEntity.setRetryCount(drawOrphanPrereserveOrderPO.getRetryCount());
        drawOrphanPrereserveOrderEntity.setNextRetryAt(drawOrphanPrereserveOrderPO.getNextRetryAt());
        drawOrphanPrereserveOrderEntity.setFinishTime(drawOrphanPrereserveOrderPO.getFinishTime());
        drawOrphanPrereserveOrderEntity.setCreateTime(drawOrphanPrereserveOrderPO.getCreateTime());
        drawOrphanPrereserveOrderEntity.setUpdateTime(drawOrphanPrereserveOrderPO.getUpdateTime());
        return drawOrphanPrereserveOrderEntity;
    }

    private DrawCountPurchaseOrderPO toPurchaseOrderPO(DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity) {
        DrawCountPurchaseOrderPO drawCountPurchaseOrderPO = new DrawCountPurchaseOrderPO();
        drawCountPurchaseOrderPO.setPurchaseOrderId(drawCountPurchaseOrderEntity.getPurchaseOrderId());
        drawCountPurchaseOrderPO.setUserId(drawCountPurchaseOrderEntity.getUserId());
        drawCountPurchaseOrderPO.setQuantity(drawCountPurchaseOrderEntity.getQuantity());
        drawCountPurchaseOrderPO.setPointsCost(drawCountPurchaseOrderEntity.getPointsCost());
        drawCountPurchaseOrderPO.setState(drawCountPurchaseOrderEntity.getState().getCode());
        drawCountPurchaseOrderPO.setRetryCount(drawCountPurchaseOrderEntity.getRetryCount());
        drawCountPurchaseOrderPO.setNextRetryAt(drawCountPurchaseOrderEntity.getNextRetryAt());
        drawCountPurchaseOrderPO.setFinishTime(drawCountPurchaseOrderEntity.getFinishTime());
        drawCountPurchaseOrderPO.setCreateTime(drawCountPurchaseOrderEntity.getCreateTime());
        drawCountPurchaseOrderPO.setUpdateTime(drawCountPurchaseOrderEntity.getUpdateTime());
        return drawCountPurchaseOrderPO;
    }

    private DrawCountPurchaseOrderEntity toPurchaseOrderEntity(DrawCountPurchaseOrderPO drawCountPurchaseOrderPO) {
        if (drawCountPurchaseOrderPO == null) {
            return null;
        }
        DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity = new DrawCountPurchaseOrderEntity();
        drawCountPurchaseOrderEntity.setId(drawCountPurchaseOrderPO.getId());
        drawCountPurchaseOrderEntity.setPurchaseOrderId(drawCountPurchaseOrderPO.getPurchaseOrderId());
        drawCountPurchaseOrderEntity.setUserId(drawCountPurchaseOrderPO.getUserId());
        drawCountPurchaseOrderEntity.setQuantity(drawCountPurchaseOrderPO.getQuantity());
        drawCountPurchaseOrderEntity.setPointsCost(drawCountPurchaseOrderPO.getPointsCost());
        drawCountPurchaseOrderEntity.setState(DrawCountPurchaseStateEnum.of(drawCountPurchaseOrderPO.getState()));
        drawCountPurchaseOrderEntity.setRetryCount(drawCountPurchaseOrderPO.getRetryCount());
        drawCountPurchaseOrderEntity.setNextRetryAt(drawCountPurchaseOrderPO.getNextRetryAt());
        drawCountPurchaseOrderEntity.setFinishTime(drawCountPurchaseOrderPO.getFinishTime());
        drawCountPurchaseOrderEntity.setCreateTime(drawCountPurchaseOrderPO.getCreateTime());
        drawCountPurchaseOrderEntity.setUpdateTime(drawCountPurchaseOrderPO.getUpdateTime());
        return drawCountPurchaseOrderEntity;
    }

    /** 插一行桶（{@link #addDrawCountBucket} 的一步）；撞 uk_user_reason_ref 返回 false = 已记过 */
    private boolean insertDrawCountBucket(DrawCountBucketEntity drawCountBucketEntity) {
        DrawCountBucketPO drawCountBucketPO = new DrawCountBucketPO();
        drawCountBucketPO.setBucketId(drawCountBucketEntity.getBucketId());
        drawCountBucketPO.setUserId(drawCountBucketEntity.getUserId());
        drawCountBucketPO.setAmount(drawCountBucketEntity.getAmount());
        drawCountBucketPO.setRemain(drawCountBucketEntity.getRemain());
        drawCountBucketPO.setExpireAt(drawCountBucketEntity.getExpireAt());
        drawCountBucketPO.setReasonType(drawCountBucketEntity.getReasonType().name());
        drawCountBucketPO.setRefId(drawCountBucketEntity.getRefId());
        drawCountBucketPO.setCreateTime(drawCountBucketEntity.getCreateTime());
        drawCountBucketPO.setUpdateTime(drawCountBucketEntity.getUpdateTime());
        return drawCountDao.insertIgnoreBucket(drawCountBucketPO) > 0;
    }

    /**
     * 造一行待入账的桶：bucket_id 走统一 IdGenerator 发号（uuid-biz-id 票 07），
     * expire_at = 入账时点 + 次数有效期配置（都在事务内取）。
     */
    private DrawCountBucketEntity newDrawCountBucket(String userId, int amount, DrawCountReasonTypeEnum drawCountReasonType,
                                                     String refId, LocalDateTime now) {
        DrawCountBucketEntity drawCountBucketEntity = new DrawCountBucketEntity();
        drawCountBucketEntity.setBucketId(idGenerator.nextId());
        drawCountBucketEntity.setUserId(userId);
        drawCountBucketEntity.setAmount(amount);
        drawCountBucketEntity.setRemain(amount);
        drawCountBucketEntity.setExpireAt(now.plusDays(drawCountValidDays()));
        drawCountBucketEntity.setReasonType(drawCountReasonType);
        drawCountBucketEntity.setRefId(refId);
        drawCountBucketEntity.setCreateTime(now);
        drawCountBucketEntity.setUpdateTime(now);
        return drawCountBucketEntity;
    }

    private DrawCountBucketEntity toDrawCountBucketEntity(DrawCountBucketPO drawCountBucketPO) {
        DrawCountBucketEntity drawCountBucketEntity = new DrawCountBucketEntity();
        drawCountBucketEntity.setId(drawCountBucketPO.getId());
        drawCountBucketEntity.setBucketId(drawCountBucketPO.getBucketId());
        drawCountBucketEntity.setUserId(drawCountBucketPO.getUserId());
        drawCountBucketEntity.setAmount(drawCountBucketPO.getAmount());
        drawCountBucketEntity.setRemain(drawCountBucketPO.getRemain());
        drawCountBucketEntity.setExpireAt(drawCountBucketPO.getExpireAt());
        drawCountBucketEntity.setReasonType(DrawCountReasonTypeEnum.valueOf(drawCountBucketPO.getReasonType()));
        drawCountBucketEntity.setRefId(drawCountBucketPO.getRefId());
        drawCountBucketEntity.setCreateTime(drawCountBucketPO.getCreateTime());
        drawCountBucketEntity.setUpdateTime(drawCountBucketPO.getUpdateTime());
        return drawCountBucketEntity;
    }

    // ---------------------------------------------------------------- 对账轮写面（每日全量对账轮，纯旁路）

    @Override
    public boolean insertReconVariance(DrawReconVarianceEntity drawReconVarianceEntity) {
        // INSERT IGNORE 撞 uk_round_check_biz 返回 0 = 本轮已登记过（调度重复触发 / 重跑零副作用）
        return drawReconVarianceDao.insertIgnoreVariance(toVariancePO(drawReconVarianceEntity)) > 0;
    }

    @Override
    public List<DrawReconVarianceEntity> listPendingReconVariances() {
        return drawReconVarianceDao.listAllPending().stream().map(this::toVarianceEntity).toList();
    }

    @Override
    public boolean confirmReconVariance(Long id) {
        return drawReconVarianceDao.confirm(id) > 0;
    }

    @Override
    public boolean markReconVarianceAutoFixed(Long id) {
        return drawReconVarianceDao.markAutoFixed(id) > 0;
    }

    @Override
    public boolean markReconVarianceFalseAlarm(Long id) {
        return drawReconVarianceDao.markFalseAlarm(id) > 0;
    }

    @Override
    public long countReconVariancesByRound(String roundNo) {
        return drawReconVarianceDao.countByRound(roundNo);
    }

    @Override
    public long countReconVariancesByRoundAndState(String roundNo, DrawReconVarianceStateEnum drawReconVarianceState) {
        return drawReconVarianceDao.countByRoundAndState(roundNo, drawReconVarianceState.getCode());
    }

    @Override
    public boolean insertReconRound(DrawReconRoundEntity drawReconRoundEntity) {
        // INSERT IGNORE 撞 uk_round_no 返回 0 = 本轮已起过
        return drawReconRoundDao.insertIgnoreRound(toRoundPO(drawReconRoundEntity)) > 0;
    }

    @Override
    public boolean finishReconRound(String roundNo, LocalDateTime scanEndTime,
                                    int varianceTotal, int confirmedTotal, int autoFixedTotal) {
        return drawReconRoundDao.finishRound(roundNo, scanEndTime, varianceTotal, confirmedTotal, autoFixedTotal) > 0;
    }

    @Override
    public void sendReconAlert(DrawReconAlertMessage drawReconAlertMessage) {
        // 对账服务没有事务模板，本方法也不在事务里：单条 insert 自带隐式事务，插入后立即同步发送
        MqOutbox mqOutbox = MqOutbox.pending(Constants.RECON_ALERT_TOPIC, drawReconAlertMessage, objectMapper);
        mqOutboxDao.insert(mqOutbox);
        sendOutbox(mqOutbox);
    }

    // ---------------------------------------------------------------- Redis key 口径

    // key 口径的唯一定义在 types.draw.Constants（本域与 points / inventory 各占一段前缀）
    private static final String DRAW_KEY_PREFIX = com.betterschool.types.draw.Constants.DRAW_KEY_PREFIX;
    private static final String DRAW_BLACKLIST_USER_KEY = DRAW_KEY_PREFIX + "blacklist:user:";
    private static final String DRAW_AWARD_KEY = DRAW_KEY_PREFIX + "award:";
    private static final String DRAW_WARMUP_STATE_KEY = DRAW_KEY_PREFIX + "warmup:";

    // ---------------------------------------------------------------- PO / Entity 转换

    private RBucket<String> blacklistUserBucket(String strategyId, String userId) {
        return redissonClient.getBucket(DRAW_BLACKLIST_USER_KEY + strategyId + ":" + userId,
                StringCodec.INSTANCE);
    }

    private RBucket<String> awardBucket(String strategyId, String slot) {
        return redissonClient.getBucket(DRAW_AWARD_KEY + strategyId + ":" + slot,
                StringCodec.INSTANCE);
    }

    private DrawActivityEntity toActivityEntity(DrawActivityPO drawActivityPO) {
        if (drawActivityPO == null) {
            return null;
        }
        DrawActivityEntity drawActivityEntity = new DrawActivityEntity();
        drawActivityEntity.setId(drawActivityPO.getId());
        drawActivityEntity.setActivityId(drawActivityPO.getActivityId());
        drawActivityEntity.setActivityName(drawActivityPO.getActivityName());
        drawActivityEntity.setStrategyId(drawActivityPO.getStrategyId());
        drawActivityEntity.setBeginDateTime(drawActivityPO.getBeginDateTime());
        drawActivityEntity.setEndDateTime(drawActivityPO.getEndDateTime());
        drawActivityEntity.setState(drawActivityPO.getState());
        drawActivityEntity.setCreateTime(drawActivityPO.getCreateTime());
        drawActivityEntity.setUpdateTime(drawActivityPO.getUpdateTime());
        return drawActivityEntity;
    }

    private DrawRecordEntity toRecordEntity(DrawRecordPO drawRecordPO) {
        if (drawRecordPO == null) {
            return null;
        }
        DrawRecordEntity drawRecordEntity = new DrawRecordEntity();
        drawRecordEntity.setId(drawRecordPO.getId());
        drawRecordEntity.setRecordId(drawRecordPO.getRecordId());
        drawRecordEntity.setUserId(drawRecordPO.getUserId());
        drawRecordEntity.setActivityId(drawRecordPO.getActivityId());
        drawRecordEntity.setStrategyId(drawRecordPO.getStrategyId());
        drawRecordEntity.setAwardId(drawRecordPO.getAwardId());
        drawRecordEntity.setAwardName(drawRecordPO.getAwardName());
        drawRecordEntity.setAwardDesc(drawRecordPO.getAwardDesc());
        drawRecordEntity.setAwardType(drawRecordPO.getAwardType());
        drawRecordEntity.setLevel(drawRecordPO.getLevel());
        drawRecordEntity.setInvId(drawRecordPO.getInvId());
        drawRecordEntity.setState(drawRecordPO.getState());
        drawRecordEntity.setCreateTime(drawRecordPO.getCreateTime());
        drawRecordEntity.setUpdateTime(drawRecordPO.getUpdateTime());
        return drawRecordEntity;
    }

    private DrawStrategyAwardEntity toAwardEntity(DrawStrategyAwardPO drawStrategyAwardPO) {
        DrawStrategyAwardEntity drawStrategyAwardEntity = new DrawStrategyAwardEntity();
        drawStrategyAwardEntity.setId(drawStrategyAwardPO.getId());
        drawStrategyAwardEntity.setStrategyId(drawStrategyAwardPO.getStrategyId());
        drawStrategyAwardEntity.setAwardId(drawStrategyAwardPO.getAwardId());
        drawStrategyAwardEntity.setAwardName(drawStrategyAwardPO.getAwardName());
        drawStrategyAwardEntity.setAwardDesc(drawStrategyAwardPO.getAwardDesc());
        drawStrategyAwardEntity.setAwardType(drawStrategyAwardPO.getAwardType());
        drawStrategyAwardEntity.setAwardRate(drawStrategyAwardPO.getAwardRate());
        drawStrategyAwardEntity.setLevel(drawStrategyAwardPO.getLevel());
        drawStrategyAwardEntity.setInvId(drawStrategyAwardPO.getInvId());
        drawStrategyAwardEntity.setType(drawStrategyAwardPO.getType());
        drawStrategyAwardEntity.setSort(drawStrategyAwardPO.getSort());
        drawStrategyAwardEntity.setCreateTime(drawStrategyAwardPO.getCreateTime());
        drawStrategyAwardEntity.setUpdateTime(drawStrategyAwardPO.getUpdateTime());
        return drawStrategyAwardEntity;
    }

    private DrawReconVariancePO toVariancePO(DrawReconVarianceEntity drawReconVarianceEntity) {
        DrawReconVariancePO drawReconVariancePO = new DrawReconVariancePO();
        drawReconVariancePO.setId(drawReconVarianceEntity.getId());
        drawReconVariancePO.setRoundNo(drawReconVarianceEntity.getRoundNo());
        drawReconVariancePO.setCheckType(drawReconVarianceEntity.getCheckType() == null ? null : drawReconVarianceEntity.getCheckType().name());
        drawReconVariancePO.setScopeKey(drawReconVarianceEntity.getScopeKey());
        drawReconVariancePO.setBizId(drawReconVarianceEntity.getBizId());
        drawReconVariancePO.setExpectSnapshot(drawReconVarianceEntity.getExpectSnapshot());
        drawReconVariancePO.setActualSnapshot(drawReconVarianceEntity.getActualSnapshot());
        drawReconVariancePO.setState(drawReconVarianceEntity.getState() == null ? null : drawReconVarianceEntity.getState().getCode());
        drawReconVariancePO.setFixedTime(drawReconVarianceEntity.getFixedTime());
        drawReconVariancePO.setFinishTime(drawReconVarianceEntity.getFinishTime());
        drawReconVariancePO.setCreateTime(drawReconVarianceEntity.getCreateTime());
        drawReconVariancePO.setUpdateTime(drawReconVarianceEntity.getUpdateTime());
        return drawReconVariancePO;
    }

    private DrawReconVarianceEntity toVarianceEntity(DrawReconVariancePO drawReconVariancePO) {
        DrawReconVarianceEntity drawReconVarianceEntity = new DrawReconVarianceEntity();
        drawReconVarianceEntity.setId(drawReconVariancePO.getId());
        drawReconVarianceEntity.setRoundNo(drawReconVariancePO.getRoundNo());
        drawReconVarianceEntity.setCheckType(drawReconVariancePO.getCheckType() == null ? null : DrawReconCheckTypeEnum.valueOf(drawReconVariancePO.getCheckType()));
        drawReconVarianceEntity.setScopeKey(drawReconVariancePO.getScopeKey());
        drawReconVarianceEntity.setBizId(drawReconVariancePO.getBizId());
        drawReconVarianceEntity.setExpectSnapshot(drawReconVariancePO.getExpectSnapshot());
        drawReconVarianceEntity.setActualSnapshot(drawReconVariancePO.getActualSnapshot());
        drawReconVarianceEntity.setState(toVarianceState(drawReconVariancePO.getState()));
        drawReconVarianceEntity.setFixedTime(drawReconVariancePO.getFixedTime());
        drawReconVarianceEntity.setFinishTime(drawReconVariancePO.getFinishTime());
        drawReconVarianceEntity.setCreateTime(drawReconVariancePO.getCreateTime());
        drawReconVarianceEntity.setUpdateTime(drawReconVariancePO.getUpdateTime());
        return drawReconVarianceEntity;
    }

    private DrawReconRoundPO toRoundPO(DrawReconRoundEntity drawReconRoundEntity) {
        DrawReconRoundPO drawReconRoundPO = new DrawReconRoundPO();
        drawReconRoundPO.setId(drawReconRoundEntity.getId());
        drawReconRoundPO.setRoundNo(drawReconRoundEntity.getRoundNo());
        drawReconRoundPO.setScanStartTime(drawReconRoundEntity.getScanStartTime());
        drawReconRoundPO.setScanEndTime(drawReconRoundEntity.getScanEndTime());
        drawReconRoundPO.setVarianceTotal(drawReconRoundEntity.getVarianceTotal());
        drawReconRoundPO.setConfirmedTotal(drawReconRoundEntity.getConfirmedTotal());
        drawReconRoundPO.setAutoFixedTotal(drawReconRoundEntity.getAutoFixedTotal());
        drawReconRoundPO.setCreateTime(drawReconRoundEntity.getCreateTime());
        drawReconRoundPO.setUpdateTime(drawReconRoundEntity.getUpdateTime());
        return drawReconRoundPO;
    }

    private DrawReconVarianceStateEnum toVarianceState(Integer code) {
        if (code == null) {
            return null;
        }
        for (DrawReconVarianceStateEnum drawReconVarianceState : DrawReconVarianceStateEnum.values()) {
            if (drawReconVarianceState.getCode() == code) {
                return drawReconVarianceState;
            }
        }
        throw new IllegalArgumentException("未知差错状态码: " + code);
    }
}
