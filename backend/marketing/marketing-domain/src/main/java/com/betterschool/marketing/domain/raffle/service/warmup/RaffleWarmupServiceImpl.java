package com.betterschool.marketing.domain.raffle.service.warmup;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.entity.DrawActivityEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyAwardEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyWeightEntity;
import com.betterschool.marketing.domain.raffle.service.chain.factory.RaffleChainFactory;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.raffle.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表预热实现：概率配置 → 恒 10000 槽的 Redis 概率表（×10000 口径）。
 * <p>
 * 口径：默认表 = type=normal 奖品位按 award_rate×10000 直落槽（合计恒 = 1.0000，预加热校验）；
 * 权重表 = 该档保底奖品池内按 award_rate 归一化（最大余数法凑满 10000 槽）。特型奖品位
 * （blacklist / fallback）只缓存 awardId。概率表先删后写、链缓存失效、最后打预热态标记——
 * 整段幂等，配置变更后重跑即生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleWarmupServiceImpl implements IRaffleWarmupService {

    /** 概率表槽位总数（award_rate 为 DECIMAL(6,4)，×10000 恰好整槽） */
    private static final int TABLE_SLOTS = 10000;

    private final IRaffleRepository raffleRepository;
    private final RaffleChainFactory raffleChainFactory;

    @Override
    public void warmupByActivityId(Long activityId) {
        DrawActivityEntity activityEntity = raffleRepository.queryActivityEntityByActivityId(activityId);
        if (activityEntity == null) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR.getCode(), "抽奖活动不存在 activityId=" + activityId);
        }
        warmupByStrategyId(activityEntity.getStrategyId());
    }

    @Override
    public void warmupByStrategyId(Long strategyId) {
        List<DrawStrategyAwardEntity> awards = raffleRepository.queryAwardListByStrategyId(strategyId);
        List<DrawStrategyAwardEntity> normalAwards = normalAwards(strategyId, awards);
        raffleRepository.cacheRaffleTable(Constants.defaultTableKey(strategyId),
                buildDefaultTable(strategyId, normalAwards));

        for (DrawStrategyWeightEntity weight : raffleRepository.queryWeightListByStrategyId(strategyId)) {
            raffleRepository.cacheRaffleTable(Constants.weightTableKey(strategyId, weight.getCount()),
                    buildWeightTable(strategyId, weight, normalAwards));
        }

        cacheSlotAward(strategyId, IRaffleRepository.SLOT_BLACKLIST, awards);
        cacheSlotAward(strategyId, IRaffleRepository.SLOT_FALLBACK, awards);

        // 规则可能随配置一起改：重预热顺带失效链缓存，下一抽按新配置重组
        raffleChainFactory.evict(strategyId);
        raffleRepository.cacheWarmupState(strategyId);
        log.info("抽奖决策表预热完成 strategyId={} normalAwards={}", strategyId, normalAwards.size());
    }

    @Override
    public boolean isWarmed(Long strategyId) {
        return raffleRepository.isWarmed(strategyId);
    }

    // ---- 表构建 ----

    private Map<Integer, Long> buildDefaultTable(Long strategyId, List<DrawStrategyAwardEntity> normalAwards) {
        // DECIMAL(6,4) → ×10000 恒为整数；合计必须恰 = 10000 槽
        int totalSlots = 0;
        List<Long> slotAwardIds = new ArrayList<>(TABLE_SLOTS);
        for (DrawStrategyAwardEntity award : normalAwards) {
            int slots = award.getAwardRate().movePointRight(4).intValueExact();
            totalSlots += slots;
            for (int i = 0; i < slots; i++) {
                slotAwardIds.add(award.getAwardId());
            }
        }
        if (totalSlots != TABLE_SLOTS) {
            throw configError("策略 %d 的 normal 奖品位概率合计 %d ≠ %d（award_rate 口径 1.0000）"
                    .formatted(strategyId, totalSlots, TABLE_SLOTS));
        }
        return toSlotMap(slotAwardIds);
    }

    private Map<Integer, Long> buildWeightTable(Long strategyId, DrawStrategyWeightEntity weight,
                                                List<DrawStrategyAwardEntity> normalAwards) {
        Map<Long, DrawStrategyAwardEntity> normalById = normalAwards.stream()
                .collect(Collectors.toMap(DrawStrategyAwardEntity::getAwardId, award -> award));
        List<DrawStrategyAwardEntity> pool = weight.getWeights().stream()
                .map(awardId -> {
                    DrawStrategyAwardEntity award = normalById.get(awardId);
                    if (award == null) {
                        throw configError("权重档引用的奖品不在 normal 池 strategyId=%d count=%d awardId=%d"
                                .formatted(strategyId, weight.getCount(), awardId));
                    }
                    return award;
                })
                .toList();
        // 池内按 award_rate 归一化到 10000 槽：base = raw×10000/Σraw，余数按最大余数法补齐
        long rawSum = 0;
        long[] raws = new long[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            raws[i] = pool.get(i).getAwardRate().movePointRight(4).longValueExact();
            rawSum += raws[i];
        }
        if (rawSum <= 0) {
            throw configError("权重档奖品池概率合计为 0 strategyId=%d count=%d"
                    .formatted(strategyId, weight.getCount()));
        }
        List<Long> slotAwardIds = new ArrayList<>(TABLE_SLOTS);
        int allocated = 0;
        for (int i = 0; i < pool.size(); i++) {
            int slots = (int) (raws[i] * TABLE_SLOTS / rawSum);
            allocated += slots;
            for (int s = 0; s < slots; s++) {
                slotAwardIds.add(pool.get(i).getAwardId());
            }
            raws[i] = raws[i] * TABLE_SLOTS % rawSum;
        }
        // 剩余槽按余数从大到小补
        Integer[] byRemainderDesc = new Integer[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            byRemainderDesc[i] = i;
        }
        java.util.Arrays.sort(byRemainderDesc, Comparator.comparingLong((Integer i) -> raws[i]).reversed());
        for (int i = 0; allocated < TABLE_SLOTS && i < pool.size(); allocated++, i++) {
            slotAwardIds.add(pool.get(byRemainderDesc[i]).getAwardId());
        }
        log.info("权重档概率表构建 strategyId={} count={} pool={}", strategyId, weight.getCount(),
                pool.stream().map(DrawStrategyAwardEntity::getAwardId).toList());
        return toSlotMap(slotAwardIds);
    }

    private Map<Integer, Long> toSlotMap(List<Long> slotAwardIds) {
        if (slotAwardIds.size() != TABLE_SLOTS) {
            throw configError("概率表槽位数 %d ≠ %d".formatted(slotAwardIds.size(), TABLE_SLOTS));
        }
        Collections.shuffle(slotAwardIds);
        Map<Integer, Long> table = new HashMap<>(TABLE_SLOTS * 2);
        for (int slot = 0; slot < slotAwardIds.size(); slot++) {
            table.put(slot, slotAwardIds.get(slot));
        }
        return table;
    }

    // ---- 杂项 ----

    private List<DrawStrategyAwardEntity> normalAwards(Long strategyId, List<DrawStrategyAwardEntity> awards) {
        List<DrawStrategyAwardEntity> normalAwards = awards.stream()
                .filter(award -> "normal".equals(award.getType())).toList();
        if (normalAwards.isEmpty()) {
            throw configError("策略 %d 没有配 normal 奖品位，无法预热概率表".formatted(strategyId));
        }
        return normalAwards;
    }

    /** 特型奖品位（blacklist / fallback）各取 sort 最小的一行缓存 awardId；没配则跳过 */
    private void cacheSlotAward(Long strategyId, String slot, List<DrawStrategyAwardEntity> awards) {
        awards.stream()
                .filter(award -> slot.equals(award.getType()))
                .findFirst()
                .ifPresent(award -> raffleRepository.cacheAwardId(strategyId, slot, award.getAwardId()));
    }

    private AppException configError(String info) {
        return new AppException(ResponseCode.CONFIG_PARAM_ERROR.getCode(), info);
    }
}
