package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.adapter.port.inventory.IInventoryDeductPort;
import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.aggregate.DrawRecordAggregate;
import com.betterschool.marketing.domain.raffle.model.entity.DrawActivityEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordDetailEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyAwardEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordActionVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawResultVO;
import com.betterschool.marketing.domain.raffle.service.chain.factory.RaffleChainFactory;
import com.betterschool.marketing.domain.raffle.service.drawcount.IDrawCountAccountService;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.LotteryDrawnMessage;
import com.betterschool.marketing.types.common.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 抽奖主链路实现（票 13）：编排次数（**同域**，抽奖次数归抽奖域）、责任链（票 11）、
 * 库存域（port）与落库，不持有跨域状态。
 * <p>
 * 三条降级 / 兜底路径：预热缺失（链尾默认节点抛 4311）→ DB 直算概率；虚拟奖品桶不足 / 实物配额不足
 * → 切兜底奖品位（type=fallback）；连兜底也扣不出 → RAFFLE_ISSUE_FAILED，本次不落任何库存。
 * <p>
 * 幂等口径：record_id = hash(userId, token) 的确定性 UUID 映射（无重放表，见
 * {@link com.betterschool.marketing.types.common.IdempotencyKeys}）。重放走「先查记录」快路径；
 * 并发双击靠扣次的明细唯一键串行化——败者要么在扣次后查到已落记录回放，要么在落库时撞
 * uk_record_id 后回放；两条路都不重复扣次数、不重复预扣。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleDrawServiceImpl implements IRaffleDrawService {

    private final IRaffleRepository raffleRepository;
    private final RaffleChainFactory raffleChainFactory;
    private final IDrawCountAccountService drawCountAccountService;
    private final IInventoryDeductPort inventoryDeductPort;

    @Override
    public DrawResultVO draw(String userId, String token) {
        if (token == null || token.isBlank()) {
            throw new AppException(ResponseCode.LOTTERY_PARAM_ERROR);
        }
        String recordId = recordIdOf(userId, token);

        DrawRecordEntity replayed = queryOwnRecord(recordId, userId);
        if (replayed != null) {
            log.info("[RaffleDraw] 幂等重放 userId={} recordId={}", userId, recordId);
            return toResult(replayed);
        }

        DrawActivityEntity activity = raffleRepository.queryCurrentActivity();
        checkActivityOpen(activity);

        // 次数扣减先于决策（rafflex 同序）：不足即拒，无任何副作用。
        // 抽奖只消耗抽奖次数，**从不直接扣积分**——积分换次数是另一条独立入口，见
        // IRaffleDrawCountPurchaseService。
        if (!drawCountAccountService.deductForDraw(userId, recordId)) {
            // 并发同 token：另一请求刚扣次落库 → 这笔是重放；真没次数才是 NO_CHANCE
            DrawRecordEntity concurrent = queryOwnRecord(recordId, userId);
            if (concurrent != null) {
                return toResult(concurrent);
            }
            throw new AppException(ResponseCode.NO_CHANCE);
        }

        List<DrawStrategyAwardEntity> awardList = raffleRepository.queryAwardListByStrategyId(activity.getStrategyId());
        Map<String, DrawStrategyAwardEntity> awardMap = awardList.stream()
                .collect(Collectors.toMap(DrawStrategyAwardEntity::getAwardId, Function.identity()));

        RaffleChainFactory.ChainAwardVO decision = decide(activity, userId, awardList);
        DrawStrategyAwardEntity award = requireAward(awardMap, decision.getAwardId());
        award = issueWithFallback(award, activity.getStrategyId(), awardMap, recordId);

        DrawRecordEntity record = persistRecord(userId, activity, recordId, award);
        return toResult(record);
    }

    /**
     * 孤儿预扣收敛专用重放（见 {@link IRaffleDrawService#replayDrawAfterPrereserve}）：
     * 编排步骤与 {@link #draw} 完全同源（决策 / 预扣 / 落库三个私有步骤复用，不另写一套），
     * 只裁掉「幂等预检 + 次数扣减」两步——扣次事实由对账单承载，记录缺失由预扣明细反证。
     */
    @Override
    public DrawResultVO replayDrawAfterPrereserve(String userId, String recordId) {
        DrawActivityEntity activity = raffleRepository.queryCurrentActivity();
        // 不可重放（查无进行中活动 / 已结束 / 未开始）抛 4301/4302，对账方转「回补 + 退还」路径
        checkActivityOpen(activity);

        List<DrawStrategyAwardEntity> awardList = raffleRepository.queryAwardListByStrategyId(activity.getStrategyId());
        Map<String, DrawStrategyAwardEntity> awardMap = awardList.stream()
                .collect(Collectors.toMap(DrawStrategyAwardEntity::getAwardId, Function.identity()));

        RaffleChainFactory.ChainAwardVO decision = decide(activity, userId, awardList);
        DrawStrategyAwardEntity award = requireAward(awardMap, decision.getAwardId());
        award = issueWithFallback(award, activity.getStrategyId(), awardMap, recordId);

        DrawRecordEntity record = persistRecord(userId, activity, recordId, award);
        return toResult(record);
    }

    // ---------------------------------------------------------------- 编排步骤

    private void checkActivityOpen(DrawActivityEntity activity) {
        if (activity == null || !"open".equals(activity.getState())) {
            throw new AppException(ResponseCode.LOTTERY_ACTIVITY_ENDED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getBeginDateTime())) {
            throw new AppException(ResponseCode.LOTTERY_ACTIVITY_NOT_STARTED);
        }
        if (now.isAfter(activity.getEndDateTime())) {
            throw new AppException(ResponseCode.LOTTERY_ACTIVITY_ENDED);
        }
    }

    /** 责任链出奖；预热缺失（4311）降级为 DB 直算概率——口径与预热同源（award_rate ×10000 归一化） */
    private RaffleChainFactory.ChainAwardVO decide(DrawActivityEntity activity, String userId,
                                                   List<DrawStrategyAwardEntity> awardList) {
        try {
            return raffleChainFactory.getFirstChainNode(activity.getStrategyId())
                    .execute(userId, activity.getStrategyId(), activity.getActivityId());
        } catch (AppException e) {
            if (!ResponseCode.RAFFLE_WARMUP_MISSING.getCode().equals(e.getCode())) {
                throw e;
            }
            log.warn("[RaffleDraw] 概率表未预热，降级 DB 直算 strategyId={} userId={}",
                    activity.getStrategyId(), userId);
            return computeFromDatabase(userId, awardList);
        }
    }

    private RaffleChainFactory.ChainAwardVO computeFromDatabase(String userId, List<DrawStrategyAwardEntity> awardList) {
        // 只有 normal 档进概率池（与预热口径一致）；rate 是 DECIMAL(6,4)，×10000 恒为整数
        List<DrawStrategyAwardEntity> normalAwards = awardList.stream()
                .filter(award -> "normal".equals(award.getType()))
                .toList();
        if (normalAwards.isEmpty()) {
            throw new AppException(ResponseCode.RAFFLE_WARMUP_MISSING);
        }
        long total = normalAwards.stream()
                .mapToLong(award -> slotsOf(award))
                .sum();
        long pick = java.util.concurrent.ThreadLocalRandom.current().nextLong(total);
        long cumulative = 0;
        for (DrawStrategyAwardEntity award : normalAwards) {
            cumulative += slotsOf(award);
            if (pick < cumulative) {
                return new RaffleChainFactory.ChainAwardVO(award.getAwardId(), null, null);
            }
        }
        // 浮点累计兜底：落到最后一个档
        DrawStrategyAwardEntity last = normalAwards.get(normalAwards.size() - 1);
        return new RaffleChainFactory.ChainAwardVO(last.getAwardId(), null, null);
    }

    private long slotsOf(DrawStrategyAwardEntity award) {
        return award.getAwardRate().multiply(BigDecimal.valueOf(10_000)).longValue();
    }

    /**
     * 库存预扣（award-inventory 两层模型）：预扣锚在奖品位 id（奖品库存账户按 award_id 1:1，
     * 投放池只被「分配」转移，抽奖永远不碰池）。奖品位投放池配置 {@code inv_id='0'}/null = 不限量哨兵，
     * 跳过预扣。实物走带兜底的预扣（票 12 的 port），虚拟走虚拟账户预扣；扣不出切兜底奖品位，
     * 兜底也扣不出 → RAFFLE_ISSUE_FAILED。
     * <p>
     * 预扣带 {@code recordId}（票 03）：库存明细以 {@code (award_id, biz_type, biz_id = recordId)}
     * 为业务唯一键，同 token 并发双击时第二笔撞键 → 库存侧按「已扣」回放，净扣减只有一份。
     */
    private DrawStrategyAwardEntity issueWithFallback(DrawStrategyAwardEntity award, String strategyId,
                                                      Map<String, DrawStrategyAwardEntity> awardMap, String recordId) {
        if (award.getInvId() == null || "0".equals(award.getInvId())) {
            return award;
        }
        DrawStrategyAwardEntity fallbackAward = findFallbackAward(strategyId, awardMap);
        if ("PHYSICAL".equals(award.getAwardType())) {
            String fallbackAwardId = fallbackAward != null && fallbackAward.getInvId() != null
                    && !"0".equals(fallbackAward.getInvId()) ? fallbackAward.getAwardId() : null;
            IInventoryDeductPort.Outcome outcome = inventoryDeductPort
                    .preDeductPhysicalWithFallback(award.getAwardId(), fallbackAwardId, 1, recordId);
            if (outcome == IInventoryDeductPort.Outcome.FALLBACK) {
                log.info("[RaffleDraw] 实物库存不足，切兜底奖品 awardId={} fallbackAwardId={}",
                        award.getAwardId(), fallbackAward.getAwardId());
                return requireFallback(fallbackAward);
            }
            if (outcome == IInventoryDeductPort.Outcome.FAILED) {
                // 兜底库存也空了；兜底奖品位若本身不限量（inv_id='0'）仍可发
                if (fallbackAward != null && fallbackAward.getInvId() != null && "0".equals(fallbackAward.getInvId())) {
                    return fallbackAward;
                }
                throw new AppException(ResponseCode.RAFFLE_ISSUE_FAILED);
            }
            return award;
        }
        if (inventoryDeductPort.preDeductVirtual(award.getAwardId(), 1, recordId)) {
            return award;
        }
        log.info("[RaffleDraw] 虚拟奖品不足，切兜底奖品 awardId={}", award.getAwardId());
        if (fallbackAward == null) {
            throw new AppException(ResponseCode.RAFFLE_ISSUE_FAILED);
        }
        if (fallbackAward.getInvId() != null && !"0".equals(fallbackAward.getInvId())
                && !inventoryDeductPort.preDeductVirtual(fallbackAward.getAwardId(), 1, recordId)) {
            throw new AppException(ResponseCode.RAFFLE_ISSUE_FAILED);
        }
        return fallbackAward;
    }

    private DrawStrategyAwardEntity requireAward(Map<String, DrawStrategyAwardEntity> awardMap, String awardId) {
        DrawStrategyAwardEntity award = awardMap.get(awardId);
        if (award == null) {
            // 链命中的 awardId 不在配置里 = 配置错 / 配置变更窗口，直接炸出来
            throw new AppException(ResponseCode.SYSTEM_ERROR);
        }
        return award;
    }

    private DrawStrategyAwardEntity findFallbackAward(String strategyId, Map<String, DrawStrategyAwardEntity> awardMap) {
        String fallbackAwardId = raffleRepository.queryAwardIdBySlot(strategyId,
                IRaffleRepository.SLOT_FALLBACK);
        return fallbackAwardId == null ? null : awardMap.get(fallbackAwardId);
    }

    private DrawStrategyAwardEntity requireFallback(DrawStrategyAwardEntity fallbackAward) {
        if (fallbackAward == null) {
            // 兜底库存扣成功、奖品位却缺失 = 配置错，预扣侧已落明细留给对账
            log.error("[RaffleDraw] 兜底奖品位缺失，无法落库");
            throw new AppException(ResponseCode.RAFFLE_ISSUE_FAILED);
        }
        return fallbackAward;
    }

    private DrawRecordEntity persistRecord(String userId, DrawActivityEntity activity, String recordId,
                                           DrawStrategyAwardEntity award) {
        LocalDateTime now = LocalDateTime.now();
        DrawRecordEntity record = new DrawRecordEntity();
        record.setRecordId(recordId);
        record.setUserId(userId);
        record.setActivityId(activity.getActivityId());
        record.setStrategyId(activity.getStrategyId());
        record.setAwardId(award.getAwardId());
        record.setAwardName(award.getAwardName());
        record.setAwardDesc(award.getAwardDesc());
        record.setAwardType(award.getAwardType());
        record.setLevel(award.getLevel());
        // inv_id 快照 = 奖品库存账户维度（award_id，award-inventory 两层模型；不限量哨兵 inv_id='0'/null 落 NULL）
        record.setInvId(award.getInvId() == null || "0".equals(award.getInvId()) ? null : award.getAwardId());
        // THANKS 落库即已完成（sql.md 口径，不走兑换/发货）；其余从待兑换起步，推进归票 14
        record.setState("THANKS".equals(award.getAwardType())
                ? DrawRecordActionVO.COMPLETE.getTargetState()
                : DrawRecordActionVO.DRAW.getTargetState());
        record.setCreateTime(now);
        record.setUpdateTime(now);

        DrawRecordDetailEntity detail = new DrawRecordDetailEntity();
        detail.setUserId(userId);
        detail.setRecordId(recordId);
        detail.setType(DrawRecordActionVO.DRAW.getCode());
        detail.setCreateTime(now);
        detail.setUpdateTime(now);

        DrawRecordAggregate aggregate = new DrawRecordAggregate();
        aggregate.setRecord(record);
        aggregate.setDetail(detail);
        aggregate.setDrawnEvent(drawnEvent(record));
        try {
            raffleRepository.insertDrawRecord(aggregate);
        } catch (DuplicateKeyException e) {
            // 并发同 token 撞 uk_record_id：另一请求已落库，回放那份
            DrawRecordEntity winner = queryOwnRecord(recordId, userId);
            if (winner == null) {
                throw e;
            }
            log.info("[RaffleDraw] 落库撞唯一键判定为并发重放 userId={} recordId={}", userId, recordId);
            return winner;
        }
        return record;
    }

    /**
     * 中奖事件（topic {@code bs-lottery-drawn}）与业务写同事务落 outbox —— 见 {@code DrawRecordAggregate}。
     * 谢谢参与不发事件（积分域与单体都不该收到「谢谢参与」）；
     * {@code pointsAmount} 只在虚拟积分档现算（经奖品位读 type=POINTS 池的面额配置），实物 / 次数档留空。
     */
    private LotteryDrawnMessage drawnEvent(DrawRecordEntity record) {
        if (!"THANKS".equals(record.getAwardType())) {
            return new LotteryDrawnMessage(record.getRecordId(), record.getUserId(), record.getAwardId(),
                    record.getAwardType(), pointsAmountOf(record));
        }
        return null;
    }

    private Integer pointsAmountOf(DrawRecordEntity record) {
        return "VIRTUAL".equals(record.getAwardType()) && record.getInvId() != null
                ? raffleRepository.queryPointsValueByAwardId(record.getInvId())
                : null;
    }

    // ---------------------------------------------------------------- 重放与出参

    /** 幂等重放必须校验归属：hash 碰撞把别人的记录顶出来时按系统错拒绝（概率可忽略，不可回放他人结果） */
    private DrawRecordEntity queryOwnRecord(String recordId, String userId) {
        DrawRecordEntity record = raffleRepository.queryRecordByRecordId(recordId);
        if (record != null && !record.getUserId().equals(userId)) {
            throw new AppException(ResponseCode.SYSTEM_ERROR);
        }
        return record;
    }

    private DrawResultVO toResult(DrawRecordEntity record) {
        return new DrawResultVO(record.getRecordId(), record.getAwardId(), record.getAwardName(),
                record.getAwardType(), record.getLevel(), pointsAmountOf(record));
    }

    /**
     * record_id = hash(userId, token) 的确定性 UUID 映射（见 {@link com.betterschool.marketing.types.common.IdempotencyKeys}）：
     * 同一 (userId, token) 恒映射同一记录——重放不需要重放表，uk_record_id 就是幂等互斥。
     * 碰撞概率 ~2^-128 量级，撞了由 uk + 归属校验兜底。
     */
    private static String recordIdOf(String userId, String token) {
        return com.betterschool.marketing.types.common.IdempotencyKeys.of(userId, token);
    }
}
