package com.betterschool.usecase.marketing;

import com.betterschool.domain.draw.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.domain.draw.model.entity.DrawOrphanPrereserveOrderEntity;
import com.betterschool.domain.draw.model.entity.DrawReconVarianceEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.domain.draw.model.entity.DrawStrategyAwardEntity;
import com.betterschool.domain.draw.model.valobj.DrawAttemptVO;
import com.betterschool.domain.draw.model.valobj.DrawCountPurchaseResultVO;
import com.betterschool.domain.draw.model.valobj.DrawResultVO;
import com.betterschool.domain.draw.model.valobj.AwardPrereserveConservationVO;
import com.betterschool.domain.draw.model.valobj.RecordMissingDeductVO;
import com.betterschool.domain.draw.model.valobj.ShippingPlanVO;
import com.betterschool.domain.draw.model.valobj.ShippingResultVO;
import com.betterschool.domain.draw.model.valobj.UnmatchedDeductDetailVO;
import com.betterschool.domain.draw.service.IDrawService;
import com.betterschool.domain.draw.service.IDrawShippingService;
import com.betterschool.domain.draw.service.drawcount.IDrawCountAccountService;
import com.betterschool.domain.draw.service.drawcount.IDrawCountPurchaseService;
import com.betterschool.domain.draw.service.recon.IDrawReconQueryService;
import com.betterschool.domain.draw.service.recon.IDrawReconService;
import com.betterschool.domain.award.model.valobj.DeductOutcomeEnum;
import com.betterschool.domain.award.model.valobj.AwardDeductRequestVO;
import com.betterschool.domain.award.model.valobj.OrphanPrereserveCandidateVO;
import com.betterschool.domain.award.service.IAwardInventoryService;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.types.common.AppException;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.DrawReconAlertMessage;
import com.betterschool.types.common.LotteryDrawnMessage;
import com.betterschool.types.draw.DrawCountPurchaseStateEnum;
import com.betterschool.types.draw.DrawOrphanPrereserveStateEnum;
import com.betterschool.types.draw.DrawReconCheckTypeEnum;
import com.betterschool.types.draw.DrawReconVarianceStateEnum;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.award.AwardInventoryTypeEnum;
import com.betterschool.types.award.AwardBizTypes;
import com.betterschool.types.points.PointsReasonTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 抽奖用例（抽奖域的唯一编排入口）：主链路、次数购买、发货闭环、孤儿预扣收敛、库存对账与营销通知。
 * <p>
 * 各段只做跨域顺序编排，不包跨域事务（ADR-0016）：事务边界在各自域仓储，幂等靠唯一键与状态守卫吸收。
 * 段落一览：
 * <ul>
 *   <li><b>抽奖</b>：domain 扣次并出奖 → 本层按奖品位预扣库存（不足切兜底）→ 回到 domain 落库；</li>
 *   <li><b>购买次数</b>：落单 CREATED → 积分域扣分 → 次数入账并置 DONE（三段各自本地事务）；</li>
 *   <li><b>发货</b>：抽奖域算计划（只读）→ 奖品域兑换确认 → 抽奖域推进状态；</li>
 *   <li><b>孤儿预扣</b>：种单 + 逐笔收敛（可重放则补落库，不可重放则库存回补 + 次数退还）；</li>
 *   <li><b>对账</b>：纯旁路轧账，只写差错表 / 轮次表，守恒破坏只告警不自动修；</li>
 *   <li><b>通知</b>：中奖事件与对账告警落 BOTTOM_SYSTEM 通知（积分变动通知归 {@link PointsCase}）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawCase {

    // ---- 对账（纯旁路轮次） ----

    /** 对账数据窗口：只扫 update_time < now - 10min 的行，与孤儿种单阈值对齐，避开主链路在途窗口。 */
    static final Duration RECON_DATA_WINDOW = Duration.ofMinutes(10);

    /** 对账批大小口径：DEAD 孤儿单点名扫描与补种入口的上限。 */
    static final int RECON_SCAN_LIMIT = 100;

    /** 三条不变量按台账账户分批跑的批大小（award_id 分页口径）。 */
    static final int RECON_AWARD_BATCH_SIZE = 100;

    // ---- 孤儿预扣（逐笔收敛） ----

    /** 孤儿判定补偿阈值：预扣发生超过 10 分钟仍无抽奖记录 = 主链路落库不会再发生（宕机残留） */
    static final Duration ORPHAN_SEED_THRESHOLD = Duration.ofMinutes(10);

    /** 孤儿补偿重试上限：第 5 次尝试（retry_count ≥ 4 时认领）仍失败即置 DEAD，人工介入 */
    static final int ORPHAN_COMPENSATE_MAX_RETRY = 5;

    /** 第 n 次尝试失败后的退避档（1/2/4/8/16 分钟），由认领动作写入 next_retry_at */
    private static final List<Duration> ORPHAN_COMPENSATE_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
            Duration.ofMinutes(8), Duration.ofMinutes(16));

    // ---- 营销通知 ----

    private static final String REF_TYPE_MARKETING = "MARKETING";

    /** 事件体 prizeType 字符串值（跨服务 wire 层有意用字符串，见 LotteryDrawnMessage） */
    private static final String PRIZE_TYPE_VIRTUAL = "VIRTUAL";
    private static final String PRIZE_TYPE_THANKS = "THANKS";

    private final IDrawService drawService;
    private final IDrawShippingService drawShippingService;
    private final IDrawCountPurchaseService drawCountPurchaseService;
    private final IDrawCountAccountService drawCountAccountService;
    private final IDrawReconQueryService drawReconQueryService;
    private final IDrawReconService drawReconService;
    private final IAwardInventoryService awardInventoryService;
    private final IPointsAccountService pointsAccountService;
    private final INotificationService notificationService;
    private final BatchCompensation batchCompensation;

    // ================================================================ 抽奖主链路

    /** 单抽（HTTP {@code POST /api/lottery/draw}） */
    public DrawResultVO draw(String userId, String token) {
        DrawAttemptVO drawAttemptVO = drawService.prepareDraw(userId, token);
        if (drawAttemptVO.getReplayResult() != null) {
            return drawAttemptVO.getReplayResult();
        }
        DrawStrategyAwardEntity drawStrategyAwardEntity = issueWithFallback(drawAttemptVO);
        return drawService.finishDraw(drawAttemptVO, drawStrategyAwardEntity);
    }

    /**
     * 孤儿预扣收敛专用重放：以原 recordId 重放「决策 → 库存预扣 → 落库」后半段。
     * 不扣次数；预扣撞唯一键按已扣回放。活动不可重放抛 {@code LOTTERY_ACTIVITY_*}。
     */
    private DrawResultVO replayDrawAfterPrereserve(String userId, String recordId) {
        DrawAttemptVO drawAttemptVO = drawService.prepareReplay(userId, recordId);
        DrawStrategyAwardEntity drawStrategyAwardEntity = issueWithFallback(drawAttemptVO);
        return drawService.finishDraw(drawAttemptVO, drawStrategyAwardEntity);
    }

    /**
     * 库存预扣（奖品库存模型）：预扣锚在奖品位 id（奖品库存账户按 award_id 1:1）。
     * 奖品位投放池配置 inv_id='0'/null = 不限量哨兵，跳过预扣。实物走带兜底的预扣，虚拟走虚拟账户预扣；
     * 扣不出切兜底奖品位，兜底也扣不出 → DRAW_ISSUE_FAILED。
     * <p>
     * 预扣带 recordId：库存明细以 (award_id, biz_type, biz_id=recordId) 为业务唯一键，
     * 同 token 并发双击时第二笔撞键 → 库存侧按已扣回放，净扣减只有一份。
     */
    private DrawStrategyAwardEntity issueWithFallback(DrawAttemptVO drawAttemptVO) {
        DrawStrategyAwardEntity award = drawAttemptVO.getSelectedAward();
        if (award == null) {
            throw new AppException(ResponseCodeEnum.SYSTEM_ERROR);
        }
        if (award.getInvId() == null || "0".equals(award.getInvId())) {
            return award;
        }
        DrawStrategyAwardEntity fallbackAward = drawAttemptVO.getFallbackAward();
        if ("PHYSICAL".equals(award.getAwardType())) {
            String fallbackAwardId = fallbackAward != null && fallbackAward.getInvId() != null
                    && !"0".equals(fallbackAward.getInvId()) ? fallbackAward.getAwardId() : null;
            AwardDeductRequestVO primary = new AwardDeductRequestVO(
                    AwardInventoryTypeEnum.PHYSICAL, award.getAwardId(), 1,
                    AwardBizTypes.LOTTERY_DRAW, drawAttemptVO.getRecordId());
            AwardDeductRequestVO fallback = fallbackAwardId == null ? null
                    : new AwardDeductRequestVO(AwardInventoryTypeEnum.VIRTUAL, fallbackAwardId, 1,
                    AwardBizTypes.LOTTERY_DRAW, drawAttemptVO.getRecordId());
            DeductOutcomeEnum deductOutcomeVO = awardInventoryService.preDeductWithFallback(primary, fallback);
            return switch (deductOutcomeVO) {
                case DEDUCTED -> award;
                case FALLBACK -> {
                    log.info("[DrawCase] 实物库存不足，切兜底奖品 awardId={} fallbackAwardId={}",
                            award.getAwardId(), fallbackAwardId);
                    yield requireFallback(fallbackAward);
                }
                case FAILED -> {
                    // 兜底库存也空了；兜底奖品位若本身不限量（inv_id='0'）仍可发
                    if (fallbackAward != null && fallbackAward.getInvId() != null
                            && "0".equals(fallbackAward.getInvId())) {
                        yield fallbackAward;
                    }
                    throw new AppException(ResponseCodeEnum.DRAW_ISSUE_FAILED);
                }
            };
        }
        if (awardInventoryService.preDeduct(AwardInventoryTypeEnum.VIRTUAL, award.getAwardId(), 1,
                AwardBizTypes.LOTTERY_DRAW, drawAttemptVO.getRecordId())) {
            return award;
        }
        log.info("[DrawCase] 虚拟奖品不足，切兜底奖品 awardId={}", award.getAwardId());
        if (fallbackAward == null) {
            throw new AppException(ResponseCodeEnum.DRAW_ISSUE_FAILED);
        }
        if (fallbackAward.getInvId() != null && !"0".equals(fallbackAward.getInvId())
                && !awardInventoryService.preDeduct(AwardInventoryTypeEnum.VIRTUAL, fallbackAward.getAwardId(), 1,
                AwardBizTypes.LOTTERY_DRAW, drawAttemptVO.getRecordId())) {
            throw new AppException(ResponseCodeEnum.DRAW_ISSUE_FAILED);
        }
        return fallbackAward;
    }

    private DrawStrategyAwardEntity requireFallback(DrawStrategyAwardEntity drawStrategyAwardEntity) {
        if (drawStrategyAwardEntity == null) {
            // 兜底库存扣成功、奖品位却缺失 = 配置错，预扣侧已落明细留给对账
            log.error("[DrawCase] 兜底奖品位缺失，无法落库");
            throw new AppException(ResponseCodeEnum.DRAW_ISSUE_FAILED);
        }
        return drawStrategyAwardEntity;
    }

    // ================================================================ 用积分购买次数

    /**
     * 购买入口（HTTP {@code POST /api/lottery/purchase/draw-count}）：DONE 重放纯读返回；
     * 其余状态顺势补齐三段（每段幂等，重复执行零副作用）。
     * <p>
     * 三段编排（ADR-0016 票 C：组合事务端口退役，事务边界 = 仓储）：
     * <ol>
     *   <li>落单段（draw 仓储）：购买单 {@code CREATED}——订单兼幂等键与对账源；</li>
     *   <li>扣分段（points 仓储）：{@code deduct} 流水先行（唯一键
     *       {@code (userId, REDEEM_DRAW_COUNT, purchaseOrderId)} 幂等）——资金段先行；</li>
     *   <li>推进段（draw 仓储）：次数入账（桶唯一键幂等）+ 条件更新置 {@code DONE}。</li>
     * </ol>
     * 积分不足是业务永久失败：单即置 {@code DEAD}（不占补偿重试轮次），一分未扣；
     * 用户充值后带原 token 重放即自愈。
     */
    public DrawCountPurchaseResultVO purchaseDrawCount(String userId, String token, int quantity) {
        DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity = drawCountPurchaseService.createOrder(userId, token, quantity);
        if (drawCountPurchaseOrderEntity.getState() != DrawCountPurchaseStateEnum.DONE) {
            deductForOrder(drawCountPurchaseOrderEntity);
            drawCountPurchaseService.completeOrder(drawCountPurchaseOrderEntity.getPurchaseOrderId(),
                    drawCountPurchaseOrderEntity.getUserId(), drawCountPurchaseOrderEntity.getQuantity());
        }
        return drawCountPurchaseService.resultOf(drawCountPurchaseOrderEntity);
    }

    /**
     * 购买单补偿（xxl {@code drawCountPurchaseScan} 调度）：扫到点的 {@code CREATED} 单，
     * 抢占式认领（条件更新 + 退避）后补齐三段——扣分（流水幂等，已扣过即跳过）→ 入账 → 置 DONE。
     * 重试耗尽（{@link IDrawCountPurchaseService#COMPENSATE_MAX_RETRY}）置 DEAD 等人工介入。
     *
     * @return 本轮真正收敛（置 DONE）的单数
     */
    public int compensateDuePurchaseOrders(int max) {
        int completed = 0;
        for (DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity : drawCountPurchaseService.listDueOrders(max)) {
            // 抢占：条件更新递增 retry_count 并写退避时点；被其他实例抢先 / 状态漂移则跳过
            if (!drawCountPurchaseService.claimDueOrder(drawCountPurchaseOrderEntity)) {
                continue;
            }
            // 逐单隔离：一笔失败只处置本单（退避重试 / 耗尽置死信），不中断本轮批次
            boolean converged = batchCompensation.runOne(
                    () -> compensatePurchaseOrder(drawCountPurchaseOrderEntity),
                    e -> handlePurchaseOrderFailure(drawCountPurchaseOrderEntity, e));
            if (!converged) {
                continue;
            }
            completed++;
            log.info("[DrawCase] 购买单补偿收敛 purchaseOrderId={} attempt={}",
                    drawCountPurchaseOrderEntity.getPurchaseOrderId(), drawCountPurchaseOrderEntity.getRetryCount() + 1);
        }
        return completed;
    }

    /** 购买单补偿单笔：扣分（幂等）→ 入账 + 置 DONE */
    private void compensatePurchaseOrder(DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity) {
        deductForOrder(drawCountPurchaseOrderEntity);
        drawCountPurchaseService.completeOrder(drawCountPurchaseOrderEntity.getPurchaseOrderId(),
                drawCountPurchaseOrderEntity.getUserId(), drawCountPurchaseOrderEntity.getQuantity());
    }

    private void handlePurchaseOrderFailure(DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity, Exception e) {
        log.error("[DrawCase] 购买单补偿失败 purchaseOrderId={} attempt={}",
                drawCountPurchaseOrderEntity.getPurchaseOrderId(), drawCountPurchaseOrderEntity.getRetryCount() + 1, e);
        if (drawCountPurchaseOrderEntity.getRetryCount() + 1 >= IDrawCountPurchaseService.COMPENSATE_MAX_RETRY) {
            drawCountPurchaseService.markOrderDead(drawCountPurchaseOrderEntity.getPurchaseOrderId());
            log.error("[DrawCase] 购买单进入死信，需人工介入 purchaseOrderId={}",
                    drawCountPurchaseOrderEntity.getPurchaseOrderId());
        }
    }

    /**
     * 第二段：积分域扣分（points 仓储本地事务，流水先行幂等）。
     * 「积分不足」以业务码落地——单置 DEAD 后抛 {@code LOTTERY_INSUFFICIENT_POINTS}（语义不被抹平）；
     * 其余失败抛系统异常，单留 CREATED 由扫描收敛（瞬时故障自愈）。
     */
    private void deductForOrder(DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity) {
        PointsDeductResultVO pointsDeductResultVO = pointsAccountService.deduct(drawCountPurchaseOrderEntity.getUserId(), drawCountPurchaseOrderEntity.getPointsCost(),
                PointsReasonTypeEnum.REDEEM_DRAW_COUNT, drawCountPurchaseOrderEntity.getPurchaseOrderId());
        if (pointsDeductResultVO.isSuccess()) {
            return;
        }
        if (ResponseCodeEnum.POINTS_INSUFFICIENT_POINTS.getCode().equals(pointsDeductResultVO.getCode())) {
            drawCountPurchaseService.markOrderDead(drawCountPurchaseOrderEntity.getPurchaseOrderId());
            log.error("[DrawCase] 购买单积分不足置死信（充值后带原 token 重放自愈）purchaseOrderId={}",
                    drawCountPurchaseOrderEntity.getPurchaseOrderId());
            throw new AppException(ResponseCodeEnum.LOTTERY_INSUFFICIENT_POINTS);
        }
        log.warn("[DrawCase] 购买扣分失败 purchaseOrderId={} code={}",
                drawCountPurchaseOrderEntity.getPurchaseOrderId(), pointsDeductResultVO.getCode());
        throw new AppException(ResponseCodeEnum.SYSTEM_ERROR);
    }

    // ================================================================ 发货闭环

    /**
     * 中奖事件驱动的发货：实物档**先**做库存兑换确认、**再**推进状态到已发货；
     * 虚拟 / 次数档只推进状态（入账由消费同一事件的积分域负责）。
     * <p>
     * 顺序（三步都可重复执行；失败靠 MQ 重投）：抽奖域算计划（只读）→ 奖品域兑换确认
     * （落 {@code flow_type = 2} 明细 + waiting → issued，同步合并，返回即已提交）→
     * 抽奖域推进状态 + 动作明细。真正兜底的是两段各自的幂等（库存撞明细唯一键当成功回放，
     * 抽奖侧状态机在起点条件上落空），所以「确认已提交、推进未提交」之间崩溃重投零副作用。
     */
    public ShippingResultVO shipAfterDraw(String userId, String recordId) {
        ShippingPlanVO shippingPlanVO = drawShippingService.planShipAfterDraw(userId, recordId);
        if (!shippingPlanVO.isActive()) {
            // 重复投递 / 已终态 / 谢谢参与 / 查无记录：三步都不做
            return new ShippingResultVO(recordId, null, false);
        }
        ShippingPlanVO.QuotaConfirmVO quota = shippingPlanVO.getQuotaToConfirm();
        if (quota != null) {
            // 同步合并一组一事务（flow_type = 2 明细 + waiting→issued）：返回即已提交，没有第二步；
            // quota.getInvId() 取自 draw_record.inv_id 快照 = 奖品位 id（奖品库存账户维度）
            awardInventoryService.confirmIssued(quota.getInvId(), AwardBizTypes.LOTTERY_CLAIM,
                    recordId, quota.getQuantity());
            log.info("[DrawCase] 实物奖品兑换确认已提交 recordId={} awardId={}", recordId, quota.getInvId());
        }
        return drawShippingService.advanceShipAfterDraw(userId, shippingPlanVO);
    }

    // ================================================================ 孤儿预扣收敛

    /** 对账一轮（xxl {@code orphanPrereserveScan} 的唯一入口）：先种单，再补偿。返回本轮收敛的对账单数。 */
    public int reconcile(int limit) {
        seedOrphanPrereserves(limit);
        return compensateDueOrphanOrders(limit);
    }

    /** 种单：扫库存预扣明细中的孤儿，逐行 INSERT IGNORE 种对账单。 */
    private int seedOrphanPrereserves(int limit) {
        LocalDateTime before = LocalDateTime.now().minus(ORPHAN_SEED_THRESHOLD);
        int seeded = 0;
        for (OrphanPrereserveCandidateVO orphanPrereserveCandidateVO : awardInventoryService.listOrphanPrereserves(before, limit)) {
            // user_id 快照自扣次明细反查：孤儿口径下扣次必然已发生（主链路先扣次后预扣），
            // 明细行不在 = 数据破坏，留 ERROR 本周期跳过（下轮扫描仍会重试种单）
            String userId = drawReconService.queryUserIdByDrawCountRef(orphanPrereserveCandidateVO.bizId());
            if (userId == null) {
                log.error("[DrawCase] 孤儿预扣缺扣次明细（数据破坏），本周期跳过 recordId={}", orphanPrereserveCandidateVO.bizId());
                continue;
            }
            if (drawReconService.seedOrphanPrereserveOrder(orphanPrereserveCandidateVO.bizId(),
                    orphanPrereserveCandidateVO.awardId(), userId, orphanPrereserveCandidateVO.quantity())) {
                seeded++;
                log.info("[DrawCase] 种孤儿预扣对账单 recordId={} awardId={} quantity={}",
                        orphanPrereserveCandidateVO.bizId(), orphanPrereserveCandidateVO.awardId(), orphanPrereserveCandidateVO.quantity());
            }
        }
        return seeded;
    }

    /** 补偿：到期待收敛单逐笔抢占后收敛；失败按退避重试，耗尽置 DEAD。 */
    private int compensateDueOrphanOrders(int limit) {
        List<DrawOrphanPrereserveOrderEntity> dueOrders = drawReconService.listDueOrphanPrereserveOrders(limit);
        int converged = 0;
        for (DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity : dueOrders) {
            // 抢占：条件更新递增 retry_count 并写退避时点；被其他实例抢占 / 状态漂移则跳过
            if (!drawReconService.claimOrphanPrereserveOrder(drawOrphanPrereserveOrderEntity.getId(), nextBackoffAt(drawOrphanPrereserveOrderEntity.getRetryCount()))) {
                continue;
            }
            // 逐单隔离：一笔失败只处置本单（退避重试 / 耗尽置死信），不中断本轮批次
            boolean convergedOne = batchCompensation.runOne(
                    () -> converge(drawOrphanPrereserveOrderEntity),
                    e -> handleOrphanOrderFailure(drawOrphanPrereserveOrderEntity, e));
            if (!convergedOne) {
                continue;
            }
            converged++;
            log.info("[DrawCase] 孤儿预扣收敛 recordId={} attempt={}",
                    drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getRetryCount() + 1);
        }
        return converged;
    }

    private void handleOrphanOrderFailure(DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity, Exception e) {
        log.error("[DrawCase] 孤儿预扣收敛失败 recordId={} attempt={}",
                drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getRetryCount() + 1, e);
        if (drawOrphanPrereserveOrderEntity.getRetryCount() + 1 >= ORPHAN_COMPENSATE_MAX_RETRY) {
            drawReconService.markOrphanPrereserveOrderDead(drawOrphanPrereserveOrderEntity.getId());
            log.error("[DrawCase] 孤儿预扣进入死信，需人工介入（人工 SQL 置回 ORPHAN 重新可扫）"
                    + " recordId={} awardId={}", drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getAwardId());
        }
    }

    /** 两条收敛路径：先看记录现状（已落 = 只剩漂移归还 + DONE），未落才试重放。 */
    private void converge(DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity) {
        // 快路径：记录已落库（客户端同 token 重放 / 本单前次部分成功的收敛）——漂移时补归还原奖品预扣
        DrawRecordEntity drawRecordEntity = drawReconService.queryRecordByRecordId(drawOrphanPrereserveOrderEntity.getRecordId());
        if (drawRecordEntity != null) {
            restituteIfDrift(drawOrphanPrereserveOrderEntity, drawRecordEntity.getAwardId());
            drawReconService.finishOrphanPrereserveOrder(drawOrphanPrereserveOrderEntity.getRecordId());
            return;
        }
        if (drawService.hasOpenActivity()) {
            // 路径①：决策可重放（活动仍有效）→ 以原幂等键重放落库后半段
            DrawResultVO drawResultVO = replayDrawAfterPrereserve(drawOrphanPrereserveOrderEntity.getUserId(), drawOrphanPrereserveOrderEntity.getRecordId());
            restituteIfDrift(drawOrphanPrereserveOrderEntity, drawResultVO.getAwardId());
        } else {
            // 路径②：决策不可重放（活动已结束 / 下线）→ 库存回补 + 次数退还（退还本身幂等）
            restituteOrphan(drawOrphanPrereserveOrderEntity);
            drawCountAccountService.refundForOrphanDraw(drawOrphanPrereserveOrderEntity.getUserId(), drawOrphanPrereserveOrderEntity.getRecordId(),
                    drawOrphanPrereserveOrderEntity.getQuantity().intValue());
        }
        drawReconService.finishOrphanPrereserveOrder(drawOrphanPrereserveOrderEntity.getRecordId());
    }

    /** 决策重跑可能得到与原决策不同的奖品（接受该漂移）：原奖品的预扣已成孤儿，归还其库存。 */
    private void restituteIfDrift(DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity, String landedAwardId) {
        if (!drawOrphanPrereserveOrderEntity.getAwardId().equals(landedAwardId)) {
            log.info("[DrawCase] 决策重跑漂移 recordId={} 原奖品 {} 新奖品 {}",
                    drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getAwardId(), landedAwardId);
            restituteOrphan(drawOrphanPrereserveOrderEntity);
        }
    }

    /** 库存回补（奖品域）：守卫不过返回 false：已回补过或不变量破坏，留 ERROR 后继续收敛。 */
    private void restituteOrphan(DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity) {
        boolean restituted = awardInventoryService.restitutePrereserve(
                drawOrphanPrereserveOrderEntity.getAwardId(), AwardBizTypes.LOTTERY_DRAW, drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getQuantity());
        if (!restituted) {
            log.error("[DrawCase] 库存回补守卫不过（可能已回补过或不变量破坏），留人工核对"
                    + " recordId={} awardId={} quantity={}", drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getAwardId(), drawOrphanPrereserveOrderEntity.getQuantity());
        }
    }

    /** 第 retryCount+1 次尝试的退避档下标（封顶最后一档 16 分钟）。 */
    private LocalDateTime nextBackoffAt(Integer retryCount) {
        int index = Math.min(retryCount == null ? 0 : retryCount, ORPHAN_COMPENSATE_BACKOFF.size() - 1);
        return LocalDateTime.now().plus(ORPHAN_COMPENSATE_BACKOFF.get(Math.max(index, 0)));
    }

    // ================================================================ 抽奖库存全量对账

    /**
     * 对账一轮（xxl {@code drawReconScan} 的唯一入口），返回本轮处理（二次核对 + DEAD 点名）的差错数。
     * <p>
     * 纯旁路：业务表只读，写面仅差错表 / 轮次表；守恒破坏（B3）与反向缺失只告警绝不自动修。
     * 幂等由差错表 uk_round_check_biz + 轮次表 uk_round_no 承担，调度重复触发 / 同日重跑零副作用。
     */
    public int scan(String roundNo) {
        // 段一：起轮（撞 uk_round_no 幂等跳过，同日重跑零副作用）
        LocalDateTime now = LocalDateTime.now();
        drawReconService.startReconRound(roundNo);

        // 段二：轧账——按台账账户分页逐批跑三条不变量，批间差异行合并成一份清单统一登记 PENDING
        LocalDateTime before = now.minus(RECON_DATA_WINDOW);
        List<DrawReconVarianceEntity> varianceDrafts = new ArrayList<>();
        String cursorAwardId = "";
        while (true) {
            List<String> awardIdBatch = drawReconQueryService.listLedgerAwardIds(cursorAwardId, RECON_AWARD_BATCH_SIZE);
            if (awardIdBatch.isEmpty()) {
                break;
            }
            cursorAwardId = awardIdBatch.get(awardIdBatch.size() - 1);
            collectPrereserveConservation(roundNo, before, awardIdBatch, varianceDrafts);
            collectForwardPairing(roundNo, before, awardIdBatch, varianceDrafts);
            collectReversePairing(roundNo, before, awardIdBatch, varianceDrafts);
            if (awardIdBatch.size() < RECON_AWARD_BATCH_SIZE) {
                break;
            }
        }
        Set<String> currentKeys = new HashSet<>();
        int registered = registerVarianceDrafts(varianceDrafts, currentKeys);

        // 段三：DEAD 孤儿单点名（死信是已确认事实，不走二次核对）
        int patrolled = patrolDeadOrphanOrders(roundNo);

        // 段四：二次核对与平账分流（复查全部轮次的 PENDING，不分轮次）
        int processed = recheckPendingVariances(roundNo, currentKeys);

        // 段五：终轮回填（一轮一行无状态机，重跑按计数覆盖无害）
        int varianceTotal = (int) drawReconService.countReconVariancesByRound(roundNo);
        int confirmedTotal = (int) drawReconService
                .countReconVariancesByRoundAndState(roundNo, DrawReconVarianceStateEnum.CONFIRMED);
        int autoFixedTotal = (int) drawReconService
                .countReconVariancesByRoundAndState(roundNo, DrawReconVarianceStateEnum.AUTO_FIXED);
        drawReconService.finishReconRound(roundNo, varianceTotal, confirmedTotal, autoFixedTotal);

        log.info("[DrawCase] 全量对账轮完成 roundNo={} 新登记PENDING={} DEAD点名={} 二次核对处理={} "
                        + "轮次计数(total={}, confirmed={}, autoFixed={})",
                roundNo, registered, patrolled, processed, varianceTotal, confirmedTotal, autoFixedTotal);
        return processed + patrolled;
    }

    // ---------------------------------------------------------------- 段二：轧账登记

    private void collectPrereserveConservation(String roundNo, LocalDateTime before, List<String> awardIdBatch,
                                               List<DrawReconVarianceEntity> varianceDrafts) {
        for (AwardPrereserveConservationVO awardPrereserveConservationVO : drawReconQueryService
                .queryPrereserveConservation(before, awardIdBatch)) {
            long expected = nullToZero(awardPrereserveConservationVO.getWaitingQty()) + nullToZero(awardPrereserveConservationVO.getIssuedQty());
            long actual = nullToZero(awardPrereserveConservationVO.getDeductedQty());
            if (expected == actual) {
                continue;
            }
            varianceDrafts.add(newVarianceEntity(roundNo, DrawReconVarianceStateEnum.PENDING,
                    DrawReconCheckTypeEnum.B3_CONSERVATION, awardPrereserveConservationVO.getAwardId(), awardPrereserveConservationVO.getAwardId(),
                    String.format("waiting(%d)+issued(%d)=%d", nullToZero(awardPrereserveConservationVO.getWaitingQty()),
                            nullToZero(awardPrereserveConservationVO.getIssuedQty()), expected),
                    String.format("Σ(flow_type=1 预扣明细)=%d", actual)));
        }
    }

    private void collectForwardPairing(String roundNo, LocalDateTime before, List<String> awardIdBatch,
                                       List<DrawReconVarianceEntity> varianceDrafts) {
        for (UnmatchedDeductDetailVO unmatchedDeductDetailVO : drawReconQueryService.listUnmatchedDeductDetails(before, awardIdBatch)) {
            varianceDrafts.add(newVarianceEntity(roundNo, DrawReconVarianceStateEnum.PENDING,
                    DrawReconCheckTypeEnum.FORWARD_PAIRING, unmatchedDeductDetailVO.getAwardId(), unmatchedDeductDetailVO.getBizId(),
                    String.format("draw_record 或孤儿对账单命中 record_id=%s（预扣 quantity=%d）",
                            unmatchedDeductDetailVO.getBizId(), nullToZero(unmatchedDeductDetailVO.getQuantity())),
                    "两侧均无命中：记录未落库且对账单未种（种单环节漏了）"));
        }
    }

    private void collectReversePairing(String roundNo, LocalDateTime before, List<String> awardIdBatch,
                                       List<DrawReconVarianceEntity> varianceDrafts) {
        for (RecordMissingDeductVO recordMissingDeductVO : drawReconQueryService.listRecordsMissingDeduct(before, awardIdBatch)) {
            varianceDrafts.add(newVarianceEntity(roundNo, DrawReconVarianceStateEnum.PENDING,
                    DrawReconCheckTypeEnum.REVERSE_PAIRING, recordMissingDeductVO.getInvId(), recordMissingDeductVO.getRecordId(),
                    String.format("预扣明细 award_id=%s biz_id=%s 存在", recordMissingDeductVO.getInvId(), recordMissingDeductVO.getRecordId()),
                    String.format("明细缺失（userId=%s）：记录在、预扣明细丢", recordMissingDeductVO.getUserId())));
        }
    }

    private int registerVarianceDrafts(List<DrawReconVarianceEntity> varianceDrafts, Set<String> currentKeys) {
        int registered = 0;
        for (DrawReconVarianceEntity drawReconVarianceEntity : varianceDrafts) {
            currentKeys.add(varianceKey(drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getBizId()));
            if (drawReconService.registerReconVariance(drawReconVarianceEntity)) {
                log.info("[DrawCase] 登记差错（待下轮复查确认）roundNo={} checkType={} scopeKey={} bizId={} 期望[{}] 实际[{}]",
                        drawReconVarianceEntity.getRoundNo(), drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getScopeKey(),
                        drawReconVarianceEntity.getBizId(), drawReconVarianceEntity.getExpectSnapshot(), drawReconVarianceEntity.getActualSnapshot());
                registered++;
            }
        }
        return registered;
    }

    // ---------------------------------------------------------------- 段三：DEAD 孤儿单点名

    private int patrolDeadOrphanOrders(String roundNo) {
        int patrolled = 0;
        for (DrawOrphanPrereserveOrderEntity drawOrphanPrereserveOrderEntity : drawReconService.listDeadOrphanPrereserveOrders(RECON_SCAN_LIMIT)) {
            String expectSnapshot = "孤儿对账单收敛完成（state=DONE）";
            String actualSnapshot = String.format("state=DEAD（重试耗尽，retryCount=%s），需人工介入",
                    drawOrphanPrereserveOrderEntity.getRetryCount());
            if (!registerVariance(roundNo, DrawReconVarianceStateEnum.CONFIRMED, DrawReconCheckTypeEnum.DEAD_ORPHAN_PATROL,
                    drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getRecordId(), expectSnapshot, actualSnapshot)) {
                continue;
            }
            sendAlert(roundNo, DrawReconCheckTypeEnum.DEAD_ORPHAN_PATROL,
                    drawOrphanPrereserveOrderEntity.getRecordId(), drawOrphanPrereserveOrderEntity.getRecordId(), expectSnapshot, actualSnapshot);
            patrolled++;
        }
        return patrolled;
    }

    private boolean registerVariance(String roundNo, DrawReconVarianceStateEnum drawReconVarianceState, DrawReconCheckTypeEnum drawReconCheckType,
                                     String scopeKey, String bizId, String expectSnapshot, String actualSnapshot) {
        DrawReconVarianceEntity drawReconVarianceEntity = newVarianceEntity(roundNo, drawReconVarianceState, drawReconCheckType, scopeKey, bizId,
                expectSnapshot, actualSnapshot);
        if (!drawReconService.registerReconVariance(drawReconVarianceEntity)) {
            return false;
        }
        if (drawReconVarianceState == DrawReconVarianceStateEnum.CONFIRMED) {
            log.warn("[DrawCase] DEAD 孤儿单点名 roundNo={} recordId={} {}", roundNo, bizId, actualSnapshot);
        } else {
            log.info("[DrawCase] 登记差错（待下轮复查确认）roundNo={} checkType={} scopeKey={} bizId={} 期望[{}] 实际[{}]",
                    roundNo, drawReconCheckType, scopeKey, bizId, expectSnapshot, actualSnapshot);
        }
        return true;
    }

    private DrawReconVarianceEntity newVarianceEntity(String roundNo, DrawReconVarianceStateEnum drawReconVarianceState,
                                                      DrawReconCheckTypeEnum drawReconCheckType, String scopeKey, String bizId,
                                                      String expectSnapshot, String actualSnapshot) {
        LocalDateTime now = LocalDateTime.now();
        DrawReconVarianceEntity drawReconVarianceEntity = new DrawReconVarianceEntity();
        drawReconVarianceEntity.setRoundNo(roundNo);
        drawReconVarianceEntity.setCheckType(drawReconCheckType);
        drawReconVarianceEntity.setScopeKey(scopeKey);
        drawReconVarianceEntity.setBizId(bizId);
        drawReconVarianceEntity.setExpectSnapshot(expectSnapshot);
        drawReconVarianceEntity.setActualSnapshot(actualSnapshot);
        drawReconVarianceEntity.setState(drawReconVarianceState);
        drawReconVarianceEntity.setCreateTime(now);
        drawReconVarianceEntity.setUpdateTime(now);
        return drawReconVarianceEntity;
    }

    // ---------------------------------------------------------------- 段四：二次核对与平账分流

    private int recheckPendingVariances(String roundNo, Set<String> currentKeys) {
        int processed = 0;
        for (DrawReconVarianceEntity drawReconVarianceEntity : drawReconService.listPendingReconVariances()) {
            if (!currentKeys.contains(varianceKey(drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getBizId()))) {
                drawReconService.markReconVarianceFalseAlarm(drawReconVarianceEntity.getId());
                log.info("[DrawCase] 复查证伪（假差错收口）登记轮次 {} checkType={} bizId={}",
                        drawReconVarianceEntity.getRoundNo(), drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getBizId());
                processed++;
                continue;
            }
            // 条件更新 0→1：false = 已被并发推进/证伪，跳过
            if (!drawReconService.confirmReconVariance(drawReconVarianceEntity.getId())) {
                continue;
            }
            routeConfirmed(roundNo, drawReconVarianceEntity);
            processed++;
        }
        return processed;
    }

    private void routeConfirmed(String roundNo, DrawReconVarianceEntity drawReconVarianceEntity) {
        if (drawReconVarianceEntity.getCheckType() == DrawReconCheckTypeEnum.FORWARD_PAIRING) {
            routeForwardPairing(roundNo, drawReconVarianceEntity);
            return;
        }
        // B3 守恒破坏与反向缺失 = 数据破坏级：只告警人工，绝不自动修
        sendAlert(roundNo, drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getScopeKey(), drawReconVarianceEntity.getBizId(),
                drawReconVarianceEntity.getExpectSnapshot(), drawReconVarianceEntity.getActualSnapshot());
        log.error("[DrawCase] 确认数据破坏级差异（只告警不自动修，等人工核对）roundNo={} checkType={} scopeKey={} bizId={} "
                        + "期望[{}] 实际[{}]",
                roundNo, drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getScopeKey(), drawReconVarianceEntity.getBizId(),
                drawReconVarianceEntity.getExpectSnapshot(), drawReconVarianceEntity.getActualSnapshot());
    }

    private void routeForwardPairing(String roundNo, DrawReconVarianceEntity drawReconVarianceEntity) {
        String recordId = drawReconVarianceEntity.getBizId();
        DrawOrphanPrereserveOrderEntity orderEntity = drawReconService.queryOrphanPrereserveOrderByRecordId(recordId);
        if (orderEntity == null) {
            // 复用孤儿种单入口补种；种完再查一次，命中即视为自动修复
            seedOrphanPrereserves(RECON_SCAN_LIMIT);
            DrawOrphanPrereserveOrderEntity seededEntity = drawReconService.queryOrphanPrereserveOrderByRecordId(recordId);
            if (seededEntity != null) {
                drawReconService.markReconVarianceAutoFixed(drawReconVarianceEntity.getId());
                log.info("[DrawCase] 正向漏结复用孤儿种单入口补种，置 AUTO_FIXED roundNo={} recordId={} awardId={}",
                        roundNo, recordId, drawReconVarianceEntity.getScopeKey());
                return;
            }
            sendAlert(roundNo, drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getScopeKey(), recordId,
                    drawReconVarianceEntity.getExpectSnapshot(),
                    "补种扫描未命中该单（不在候选序或候选被跳过，如缺扣次明细），保持 CONFIRMED，需人工核对补种");
            return;
        }
        if (orderEntity.getState() == DrawOrphanPrereserveStateEnum.DEAD) {
            sendAlert(roundNo, drawReconVarianceEntity.getCheckType(), drawReconVarianceEntity.getScopeKey(), recordId,
                    drawReconVarianceEntity.getExpectSnapshot(),
                    "对应孤儿对账单已进死信（state=DEAD），逐笔链路救不回，需人工介入");
            return;
        }
        log.info("[DrawCase] 正向漏结的孤儿对账单已存在 / 已收敛（state={}），逐笔链路接管，保持 CONFIRMED recordId={}",
                orderEntity.getState(), recordId);
    }

    // ---------------------------------------------------------------- 公共小件

    /** 发告警（经抽奖域仓储 outbox → RocketMQ → 单体消费落通知）：尽力而为，投递失败不中断本轮轧账，下轮重新点名。 */
    private void sendAlert(String roundNo, DrawReconCheckTypeEnum drawReconCheckType, String scopeKey, String bizId,
                           String expectSummary, String actualSummary) {
        String alertText = String.format(
                "抽奖库存对账告警：轮次 %s 检查[%s]确认差异，scopeKey=%s bizId=%s；期望：%s；实际：%s。请人工核对，处置后在差错表收口。",
                roundNo, drawReconCheckType.getDesc(), scopeKey, bizId, expectSummary, actualSummary);
        DrawReconAlertMessage drawReconAlertMessage = new DrawReconAlertMessage(roundNo, drawReconCheckType.name(), scopeKey,
                bizId, expectSummary, actualSummary, alertText);
        drawService.sendReconAlert(drawReconAlertMessage);
    }

    private String varianceKey(DrawReconCheckTypeEnum drawReconCheckType, String bizId) {
        return drawReconCheckType.name() + "|" + bizId;
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    // ================================================================ 营销通知

    /**
     * 中奖：文案按 prizeType 呈现（事件体不含奖品名，不反查生产方）；
     * 谢谢参与按生产方约定不发事件，防御性跳过。幂等 = 通知 dedup_key（broker 重投 / outbox 补发只落一条）。
     */
    public void notifyLotteryDrawn(LotteryDrawnMessage lotteryDrawnMessage) {
        if (PRIZE_TYPE_THANKS.equals(lotteryDrawnMessage.getPrizeType())) {
            log.warn("[DrawCase] 收到谢谢参与的中奖事件（生产方不应发出），跳过 recordId={}", lotteryDrawnMessage.getRecordId());
            return;
        }
        // 票 13 起抽奖域把虚拟档口径从 POINTS 改成 VIRTUAL；旧值仍认，避免在途消息落错文案
        String content = isVirtualPrize(lotteryDrawnMessage.getPrizeType())
                ? "恭喜您在抽奖中抽中 " + lotteryDrawnMessage.getPointsAmount() + " 积分，已发放至您的积分账户"
                : "恭喜您在抽奖中抽中实物奖品，可在活动页「我的奖品」中查看";
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(lotteryDrawnMessage.getUserId())
                .scope(NotificationScopeEnum.BOTTOM_SYSTEM.name())
                .type(NotificationTypeEnum.LOTTERY_WON.name())
                .title("抽奖中奖")
                .content(content)
                .refType(REF_TYPE_MARKETING)
                .refId(lotteryDrawnMessage.getRecordId())
                .dedupKey(marketingDedupKey("DRAWN", lotteryDrawnMessage.getRecordId()))
                .build());
        log.info("[DrawCase] 中奖通知已落 recordId={} userId={} prizeType={}",
                lotteryDrawnMessage.getRecordId(), lotteryDrawnMessage.getUserId(), lotteryDrawnMessage.getPrizeType());
    }

    /** 虚拟积分档：票 13 起是 VIRTUAL，"POINTS" 是旧模型的在途值 */
    private boolean isVirtualPrize(String prizeType) {
        return PRIZE_TYPE_VIRTUAL.equals(prizeType) || "POINTS".equals(prizeType);
    }

    /**
     * 对账告警（对账轮 drawReconScan 发现 CONFIRMED 差错 / DEAD 孤儿单时点名）：
     * 维护者告警不是用户通知——收件人落系统助手账号（通知中心 system tab 可查历史），
     * 正文优先用生产方渲染好的告警文案。dedupKey 含轮次 + 检查类型 + 单号，
     * job 重跑 / broker 重投的重复点名只落一条。
     */
    public void notifyReconAlert(DrawReconAlertMessage drawReconAlertMessage) {
        String content = drawReconAlertMessage.getAlertText() != null ? drawReconAlertMessage.getAlertText() : reconAlertContent(drawReconAlertMessage);
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(Constants.SYSTEM_USER_ID)
                .scope(NotificationScopeEnum.BOTTOM_SYSTEM.name())
                .type(NotificationTypeEnum.RECON_ALERT.name())
                .title("库存对账告警")
                .content(content)
                .refType(REF_TYPE_MARKETING)
                .refId(drawReconAlertMessage.getBizId())
                .dedupKey(marketingDedupKey("RECON", drawReconAlertMessage.getRoundNo(), drawReconAlertMessage.getCheckType(), drawReconAlertMessage.getBizId()))
                .build());
        log.info("[DrawCase] 对账告警已落 roundNo={} checkType={} scopeKey={} bizId={}",
                drawReconAlertMessage.getRoundNo(), drawReconAlertMessage.getCheckType(), drawReconAlertMessage.getScopeKey(), drawReconAlertMessage.getBizId());
    }

    /** 缺省文案：事件体没带告警文案时按其余字段拼（守恒公式形状：期望 X，实际 Y） */
    private String reconAlertContent(DrawReconAlertMessage drawReconAlertMessage) {
        StringBuilder content = new StringBuilder("对账轮 ").append(drawReconAlertMessage.getRoundNo())
                .append("：").append(drawReconAlertMessage.getCheckType()).append(" 差异");
        if (drawReconAlertMessage.getScopeKey() != null) {
            content.append("（").append(drawReconAlertMessage.getScopeKey()).append("）");
        }
        content.append("，定位 ").append(drawReconAlertMessage.getBizId());
        if (drawReconAlertMessage.getExpectSummary() != null || drawReconAlertMessage.getActualSummary() != null) {
            content.append("：期望 ").append(drawReconAlertMessage.getExpectSummary())
                    .append("，实际 ").append(drawReconAlertMessage.getActualSummary());
        }
        return content.toString();
    }

    private String marketingDedupKey(Object... parts) {
        StringBuilder key = new StringBuilder("MARKETING");
        for (Object part : parts) {
            key.append(':').append(part);
        }
        return key.toString();
    }
}
