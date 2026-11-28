package com.betterschool.domain.draw.service;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.aggregate.DrawRecordAggregate;
import com.betterschool.domain.draw.model.entity.DrawActivityEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordDetailEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.domain.draw.model.entity.DrawStrategyAwardEntity;
import com.betterschool.domain.draw.model.valobj.DrawAttemptVO;
import com.betterschool.domain.draw.model.valobj.DrawRecordActionEnum;
import com.betterschool.domain.draw.model.valobj.DrawResultVO;
import com.betterschool.domain.draw.service.chain.factory.DrawChainFactory;
import com.betterschool.domain.draw.service.drawcount.IDrawCountAccountService;
import com.betterschool.types.common.AppException;
import com.betterschool.types.common.DrawReconAlertMessage;
import com.betterschool.types.common.LotteryDrawnMessage;
import com.betterschool.types.enums.ResponseCodeEnum;
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
 * 抽奖主链路实现（票 13）：domain 只编排本域动作——次数扣减（抽奖次数归抽奖域）、
 * 责任链出奖（票 11）、落库。库存预扣跨域，由 case 层在 prepare / finish 之间执行，
 * 因此本类不注入任何他域 port。
 * <p>
 * 三条降级 / 兜底路径的库存半边在 case 层：预热缺失（链尾默认节点抛 4311）→ DB 直算概率；
 * 虚拟奖品 / 实物配额不足 → 切兜底奖品位；连兜底也扣不出 → {@code DRAW_ISSUE_FAILED}。
 * <p>
 * 幂等口径：record_id = hash(userId, token) 的确定性 UUID 映射（无重放表，见
 * {@link com.betterschool.types.common.IdempotencyKeys}）。重放走「先查记录」快路径；
 * 并发双击靠扣次的明细唯一键串行化，落库撞 uk_record_id 时回放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawServiceImpl implements IDrawService {

    private final IDrawRepository drawRepository;
    private final DrawChainFactory drawChainFactory;
    private final IDrawCountAccountService drawCountAccountService;

    @Override
    public DrawAttemptVO prepareDraw(String userId, String token) {
        if (token == null || token.isBlank()) {
            throw new AppException(ResponseCodeEnum.LOTTERY_PARAM_ERROR);
        }
        String recordId = recordIdOf(userId, token);

        DrawRecordEntity replayed = queryOwnRecord(recordId, userId);
        if (replayed != null) {
            log.info("[Draw] 幂等重放 userId={} recordId={}", userId, recordId);
            return replayAttempt(replayed);
        }

        DrawActivityEntity drawActivityEntity = drawRepository.queryCurrentActivity();
        checkActivityOpen(drawActivityEntity);

        // 次数扣减先于决策（旧实现同序）：不足即拒，无任何副作用。
        // 抽奖只消耗抽奖次数，从不直接扣积分——积分换次数是另一条独立入口。
        if (!drawCountAccountService.deductForDraw(userId, recordId)) {
            // 并发同 token：另一请求刚扣次落库 → 这笔是重放；真没次数才是 NO_CHANCE
            DrawRecordEntity concurrent = queryOwnRecord(recordId, userId);
            if (concurrent != null) {
                return replayAttempt(concurrent);
            }
            throw new AppException(ResponseCodeEnum.NO_CHANCE);
        }

        return decisionAttempt(userId, recordId, drawActivityEntity);
    }

    @Override
    public DrawAttemptVO prepareReplay(String userId, String recordId) {
        DrawActivityEntity drawActivityEntity = drawRepository.queryCurrentActivity();
        // 不可重放（查无进行中活动 / 已结束 / 未开始）→ 4301/4302，对账方转「回补 + 退还」路径
        checkActivityOpen(drawActivityEntity);
        return decisionAttempt(userId, recordId, drawActivityEntity);
    }

    @Override
    public DrawResultVO finishDraw(DrawAttemptVO drawAttemptVO, DrawStrategyAwardEntity drawStrategyAwardEntity) {
        DrawRecordEntity drawRecordEntity = persistRecord(drawAttemptVO, drawStrategyAwardEntity);
        return toResult(drawRecordEntity);
    }

    @Override
    public boolean hasOpenActivity() {
        DrawActivityEntity drawActivityEntity = drawRepository.queryCurrentActivity();
        if (drawActivityEntity == null || !"open".equals(drawActivityEntity.getState())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(drawActivityEntity.getBeginDateTime()) && !now.isAfter(drawActivityEntity.getEndDateTime());
    }

    @Override
    public void sendReconAlert(DrawReconAlertMessage drawReconAlertMessage) {
        // 尽力而为：投递失败只告警，差错已登记，下一轮对账会重新点名
        try {
            drawRepository.sendReconAlert(drawReconAlertMessage);
        } catch (Exception e) {
            log.error("[Draw] 对账告警投递失败（差错已登记，下一轮对账将重新点名）roundNo={} checkType={} bizId={}",
                    drawReconAlertMessage.getRoundNo(), drawReconAlertMessage.getCheckType(),
                    drawReconAlertMessage.getBizId(), e);
        }
    }

    // ---------------------------------------------------------------- 编排步骤

    private DrawAttemptVO replayAttempt(DrawRecordEntity record) {
        DrawAttemptVO attempt = new DrawAttemptVO();
        attempt.setRecordId(record.getRecordId());
        attempt.setUserId(record.getUserId());
        attempt.setActivityId(record.getActivityId());
        attempt.setStrategyId(record.getStrategyId());
        attempt.setReplayResult(toResult(record));
        return attempt;
    }

    private DrawAttemptVO decisionAttempt(String userId, String recordId, DrawActivityEntity drawActivityEntity) {
        List<DrawStrategyAwardEntity> awardList = drawRepository.queryAwardListByStrategyId(drawActivityEntity.getStrategyId());
        Map<String, DrawStrategyAwardEntity> awardMap = awardList.stream()
                .collect(Collectors.toMap(DrawStrategyAwardEntity::getAwardId, Function.identity()));

        DrawChainFactory.ChainAwardVO decision = decide(drawActivityEntity, userId, awardList);
        DrawStrategyAwardEntity award = requireAward(awardMap, decision.getAwardId());
        DrawStrategyAwardEntity fallbackAward = findFallbackAward(drawActivityEntity.getStrategyId(), awardMap);

        DrawAttemptVO drawAttemptVO = new DrawAttemptVO();
        drawAttemptVO.setRecordId(recordId);
        drawAttemptVO.setUserId(userId);
        drawAttemptVO.setActivityId(drawActivityEntity.getActivityId());
        drawAttemptVO.setStrategyId(drawActivityEntity.getStrategyId());
        drawAttemptVO.setSelectedAward(award);
        drawAttemptVO.setFallbackAward(fallbackAward);
        return drawAttemptVO;
    }

    private void checkActivityOpen(DrawActivityEntity drawActivityEntity) {
        if (drawActivityEntity == null || !"open".equals(drawActivityEntity.getState())) {
            throw new AppException(ResponseCodeEnum.LOTTERY_ACTIVITY_ENDED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(drawActivityEntity.getBeginDateTime())) {
            throw new AppException(ResponseCodeEnum.LOTTERY_ACTIVITY_NOT_STARTED);
        }
        if (now.isAfter(drawActivityEntity.getEndDateTime())) {
            throw new AppException(ResponseCodeEnum.LOTTERY_ACTIVITY_ENDED);
        }
    }

    /** 责任链出奖；预热缺失（4311）降级为 DB 直算概率——口径与预热同源（award_rate ×10000 归一化） */
    private DrawChainFactory.ChainAwardVO decide(DrawActivityEntity drawActivityEntity, String userId,
                                                   List<DrawStrategyAwardEntity> awardList) {
        try {
            return drawChainFactory.getFirstChainNode(drawActivityEntity.getStrategyId())
                    .execute(userId, drawActivityEntity.getStrategyId(), drawActivityEntity.getActivityId());
        } catch (AppException e) {
            if (!ResponseCodeEnum.DRAW_WARMUP_MISSING.getCode().equals(e.getCode())) {
                throw e;
            }
            log.warn("[Draw] 概率表未预热，降级 DB 直算 strategyId={} userId={}",
                    drawActivityEntity.getStrategyId(), userId);
            return computeFromDatabase(userId, awardList);
        }
    }

    private DrawChainFactory.ChainAwardVO computeFromDatabase(String userId, List<DrawStrategyAwardEntity> awardList) {
        // 只有 normal 档进概率池（与预热口径一致）；rate 是 DECIMAL(6,4)，×10000 恒为整数
        List<DrawStrategyAwardEntity> normalAwards = awardList.stream()
                .filter(award -> "normal".equals(award.getType()))
                .toList();
        if (normalAwards.isEmpty()) {
            throw new AppException(ResponseCodeEnum.DRAW_WARMUP_MISSING);
        }
        long total = normalAwards.stream()
                .mapToLong(award -> slotsOf(award))
                .sum();
        long pick = java.util.concurrent.ThreadLocalRandom.current().nextLong(total);
        long cumulative = 0;
        for (DrawStrategyAwardEntity award : normalAwards) {
            cumulative += slotsOf(award);
            if (pick < cumulative) {
                return new DrawChainFactory.ChainAwardVO(award.getAwardId(), null, null);
            }
        }
        // 浮点累计兜底：落到最后一个档
        DrawStrategyAwardEntity last = normalAwards.get(normalAwards.size() - 1);
        return new DrawChainFactory.ChainAwardVO(last.getAwardId(), null, null);
    }

    private long slotsOf(DrawStrategyAwardEntity drawStrategyAwardEntity) {
        return drawStrategyAwardEntity.getAwardRate().multiply(BigDecimal.valueOf(10_000)).longValue();
    }

    private DrawStrategyAwardEntity requireAward(Map<String, DrawStrategyAwardEntity> awardMap, String awardId) {
        DrawStrategyAwardEntity drawStrategyAwardEntity = awardMap.get(awardId);
        if (drawStrategyAwardEntity == null) {
            // 链命中的 awardId 不在配置里 = 配置错 / 配置变更窗口，直接炸出来
            throw new AppException(ResponseCodeEnum.SYSTEM_ERROR);
        }
        return drawStrategyAwardEntity;
    }

    private DrawStrategyAwardEntity findFallbackAward(String strategyId, Map<String, DrawStrategyAwardEntity> awardMap) {
        String fallbackAwardId = drawRepository.queryAwardIdBySlot(strategyId,
                IDrawRepository.SLOT_FALLBACK);
        return fallbackAwardId == null ? null : awardMap.get(fallbackAwardId);
    }

    private DrawRecordEntity persistRecord(DrawAttemptVO drawAttemptVO, DrawStrategyAwardEntity drawStrategyAwardEntity) {
        LocalDateTime now = LocalDateTime.now();
        DrawRecordEntity record = new DrawRecordEntity();
        record.setRecordId(drawAttemptVO.getRecordId());
        record.setUserId(drawAttemptVO.getUserId());
        record.setActivityId(drawAttemptVO.getActivityId());
        record.setStrategyId(drawAttemptVO.getStrategyId());
        record.setAwardId(drawStrategyAwardEntity.getAwardId());
        record.setAwardName(drawStrategyAwardEntity.getAwardName());
        record.setAwardDesc(drawStrategyAwardEntity.getAwardDesc());
        record.setAwardType(drawStrategyAwardEntity.getAwardType());
        record.setLevel(drawStrategyAwardEntity.getLevel());
        // inv_id 快照 = 奖品库存账户维度（award_id，奖品库存模型；不限量哨兵 inv_id='0'/null 落 NULL）
        record.setInvId(drawStrategyAwardEntity.getInvId() == null || "0".equals(drawStrategyAwardEntity.getInvId()) ? null : drawStrategyAwardEntity.getAwardId());
        // THANKS 落库即已完成（sql.md 口径，不走兑换/发货）；其余从待兑换起步，推进归票 14
        record.setState("THANKS".equals(drawStrategyAwardEntity.getAwardType())
                ? DrawRecordActionEnum.COMPLETE.getTargetState()
                : DrawRecordActionEnum.DRAW.getTargetState());
        record.setCreateTime(now);
        record.setUpdateTime(now);

        DrawRecordDetailEntity drawRecordDetailEntity = new DrawRecordDetailEntity();
        drawRecordDetailEntity.setUserId(drawAttemptVO.getUserId());
        drawRecordDetailEntity.setRecordId(drawAttemptVO.getRecordId());
        drawRecordDetailEntity.setType(DrawRecordActionEnum.DRAW.getCode());
        drawRecordDetailEntity.setCreateTime(now);
        drawRecordDetailEntity.setUpdateTime(now);

        DrawRecordAggregate drawRecordAggregate = new DrawRecordAggregate();
        drawRecordAggregate.setRecord(record);
        drawRecordAggregate.setDetail(drawRecordDetailEntity);
        drawRecordAggregate.setDrawnEvent(drawnEvent(record));
        try {
            drawRepository.insertDrawRecord(drawRecordAggregate);
        } catch (DuplicateKeyException e) {
            // 并发同 token 撞 uk_record_id：另一请求已落库，回放那份
            DrawRecordEntity winner = queryOwnRecord(drawAttemptVO.getRecordId(), drawAttemptVO.getUserId());
            if (winner == null) {
                throw e;
            }
            log.info("[Draw] 落库撞唯一键判定为并发重放 userId={} recordId={}",
                    drawAttemptVO.getUserId(), drawAttemptVO.getRecordId());
            return winner;
        }
        return record;
    }

    /**
     * 中奖事件（topic {@code bs-lottery-drawn}）与业务写同事务落 outbox——挂在 {@code DrawRecordAggregate}。
     * 谢谢参与不发事件；{@code pointsAmount} 只在虚拟积分档现算，实物 / 次数档留空。
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
                ? drawRepository.queryPointsValueByAwardId(record.getInvId())
                : null;
    }

    // ---------------------------------------------------------------- 重放与出参

    /** 幂等重放必须校验归属：hash 碰撞把别人的记录顶出来时按系统错拒绝（概率可忽略，不可回放他人结果） */
    private DrawRecordEntity queryOwnRecord(String recordId, String userId) {
        DrawRecordEntity drawRecordEntity = drawRepository.queryRecordByRecordId(recordId);
        if (drawRecordEntity != null && !drawRecordEntity.getUserId().equals(userId)) {
            throw new AppException(ResponseCodeEnum.SYSTEM_ERROR);
        }
        return drawRecordEntity;
    }

    private DrawResultVO toResult(DrawRecordEntity record) {
        return new DrawResultVO(record.getRecordId(), record.getAwardId(), record.getAwardName(),
                record.getAwardType(), record.getLevel(), pointsAmountOf(record));
    }

    /**
     * record_id = hash(userId, token) 的确定性 UUID 映射（见 {@link com.betterschool.types.common.IdempotencyKeys}）：
     * 同一 (userId, token) 恒映射同一记录——重放不需要重放表，uk_record_id 就是幂等互斥。
     * 碰撞概率 ~2^-128 量级，撞了由 uk + 归属校验兜底。
     */
    private static String recordIdOf(String userId, String token) {
        return com.betterschool.types.common.IdempotencyKeys.of(userId, token);
    }
}
