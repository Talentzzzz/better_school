package com.betterschool.marketing.domain.raffle.service.orphan;

import com.betterschool.marketing.domain.raffle.adapter.port.inventory.IInventoryDeductPort;
import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.entity.DrawOrphanPrereserveOrderEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawResultVO;
import com.betterschool.marketing.domain.raffle.service.IRaffleDrawService;
import com.betterschool.marketing.domain.raffle.service.drawcount.IDrawCountAccountService;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.raffle.DrawOrphanPrereserveState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿预扣对账实现：种单 + 补偿两段都只做编排与口径判断，跨域动作（库存回补）经
 * {@link IInventoryDeductPort} 委托，落库后半段重放经 {@link IRaffleDrawService#replayDrawAfterPrereserve}
 * 与主链路同源——本类不注入 {@code TransactionTemplate}，每一步都是仓储/端口自带的原子用例，
 * 跨步一致性由对账单状态机 + 幂等（落库 uk_record_id / 次数桶 uk_user_reason_ref）承担。
 * <p>
 * 收敛判据：对账单在 ⇒ 预扣已提交而记录未落库（种单时刻的快照）；收敛时先查记录现状
 * （客户端同 token 重放或前次部分成功可能已把记录落库）再走路径——记录已落即只剩
 * 「漂移归还」与置 DONE，记录未落才试重放，重放不可行（活动已结束 / 下线）才回补 + 退还。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleOrphanPrereserveServiceImpl implements IRaffleOrphanPrereserveService {

    /** 孤儿判定补偿阈值：预扣发生超过 10 分钟仍无抽奖记录 = 主链路落库不会再发生（宕机残留） */
    static final Duration SEED_THRESHOLD = Duration.ofMinutes(10);

    /** 补偿重试上限：第 5 次尝试（retry_count 达 4 时认领）仍失败即置 DEAD，人工介入 */
    static final int COMPENSATE_MAX_RETRY = 5;
    /** 第 n 次尝试失败后的退避档（1/2/4/8/16 分钟），由认领动作写进 next_retry_at */
    private static final List<Duration> COMPENSATE_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
            Duration.ofMinutes(8), Duration.ofMinutes(16));

    private final IRaffleRepository raffleRepository;
    private final IRaffleDrawService raffleDrawService;
    private final IDrawCountAccountService drawCountAccountService;
    private final IInventoryDeductPort inventoryDeductPort;

    @Override
    public int reconcile(int limit) {
        seedOrphanPrereserves(limit);
        return compensateDueOrders(limit);
    }

    @Override
    public int seedOrphanPrereserves(int limit) {
        LocalDateTime before = LocalDateTime.now().minus(SEED_THRESHOLD);
        int seeded = 0;
        for (IInventoryDeductPort.OrphanPrereserveRef orphan : inventoryDeductPort.listOrphanPrereserves(before, limit)) {
            // user_id 快照自扣次明细反查：孤儿口径下扣次必然已发生（主链路先扣次后预扣），
            // 明细行不在 = 数据破坏，留 ERROR 本周期跳过（下轮扫描仍会重试种单）
            String userId = raffleRepository.queryUserIdByDrawCountRef(orphan.recordId());
            if (userId == null) {
                log.error("[OrphanPrereserve] 孤儿预扣缺扣次明细（数据破坏），本周期跳过 recordId={}",
                        orphan.recordId());
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            DrawOrphanPrereserveOrderEntity order = new DrawOrphanPrereserveOrderEntity(
                    null, orphan.recordId(), orphan.awardId(), userId, orphan.quantity(),
                    DrawOrphanPrereserveState.ORPHAN, 0, null, null, now, now);
            if (raffleRepository.insertOrphanPrereserveOrder(order)) {
                seeded++;
                log.info("[OrphanPrereserve] 种孤儿预扣对账单 recordId={} awardId={} quantity={}",
                        orphan.recordId(), orphan.awardId(), orphan.quantity());
            }
        }
        return seeded;
    }

    @Override
    public int compensateDueOrders(int limit) {
        List<DrawOrphanPrereserveOrderEntity> dueOrders = raffleRepository.listDueOrphanPrereserveOrders(limit);
        int converged = 0;
        for (DrawOrphanPrereserveOrderEntity order : dueOrders) {
            // 抢占：条件更新递增 retry_count 并写退避时点；被其他实例抢先 / 状态漂移则跳过
            if (!raffleRepository.claimOrphanPrereserveOrder(order.getId(), nextBackoffAt(order.getRetryCount()))) {
                continue;
            }
            try {
                converge(order);
                converged++;
                log.info("[OrphanPrereserve] 孤儿预扣收敛 recordId={} attempt={}",
                        order.getRecordId(), order.getRetryCount() + 1);
            } catch (Exception e) {
                log.error("[OrphanPrereserve] 孤儿预扣收敛失败 recordId={} attempt={}",
                        order.getRecordId(), order.getRetryCount() + 1, e);
                if (order.getRetryCount() + 1 >= COMPENSATE_MAX_RETRY) {
                    raffleRepository.markOrphanPrereserveOrderDead(order.getId());
                    log.error("[OrphanPrereserve] 孤儿预扣进入死信，需人工介入（人工 SQL 置回 ORPHAN 重新可扫）"
                            + " recordId={} awardId={}", order.getRecordId(), order.getAwardId());
                }
            }
        }
        return converged;
    }

    /**
     * 两条收敛路径：先看记录现状（已落 = 只剩漂移归还 + DONE），未落才试重放，
     * 重放不可行（{@code LOTTERY_ACTIVITY_*}）才回补 + 退还。
     */
    private void converge(DrawOrphanPrereserveOrderEntity order) {
        // 快路径：记录已落库（客户端同 token 重放 / 本单前次部分成功的收敛）——漂移时补归还原奖品预扣
        DrawRecordEntity landed = raffleRepository.queryRecordByRecordId(order.getRecordId());
        if (landed != null) {
            restituteIfDrift(order, landed.getAwardId());
            raffleRepository.finishOrphanPrereserveOrder(order.getRecordId());
            return;
        }
        try {
            // 路径①：决策可重放（活动仍有效）→ 以原幂等键重放落库后半段：不重复扣次数（扣次明细在即已扣），
            // 预扣撞唯一键按已扣回放；决策重跑漂移到别的奖品时，原奖品的预扣由 restituteIfDrift 归还
            DrawResultVO result = raffleDrawService.replayDrawAfterPrereserve(order.getUserId(), order.getRecordId());
            restituteIfDrift(order, result.getAwardId());
        } catch (AppException e) {
            if (!isActivityNotReplayable(e)) {
                // 其余失败（发放失败 / 系统错）按可重试走退避，耗尽进死信
                throw e;
            }
            // 路径②：决策不可重放（活动已结束 / 下线）→ 库存回补 + 次数退还（退还本身幂等）
            restituteOrphan(order);
            drawCountAccountService.refundForOrphanDraw(order.getUserId(), order.getRecordId(),
                    order.getQuantity().intValue());
        }
        raffleRepository.finishOrphanPrereserveOrder(order.getRecordId());
    }

    /** 决策重跑可能得到与原决策不同的奖品（接受该漂移）：原奖品的预扣已成孤儿，归还其库存 */
    private void restituteIfDrift(DrawOrphanPrereserveOrderEntity order, String landedAwardId) {
        if (!order.getAwardId().equals(landedAwardId)) {
            log.info("[OrphanPrereserve] 决策重跑漂移 recordId={} 原奖品={} 新奖品={}",
                    order.getRecordId(), order.getAwardId(), landedAwardId);
            restituteOrphan(order);
        }
    }

    /** 库存回补经端口委托库存域（守卫不过返回 false：已回补过或不变量破坏，留 ERROR 后继续收敛） */
    private void restituteOrphan(DrawOrphanPrereserveOrderEntity order) {
        boolean restituted = inventoryDeductPort.restituteOrphanPrereserve(
                order.getAwardId(), order.getRecordId(), order.getQuantity());
        if (!restituted) {
            log.error("[OrphanPrereserve] 库存回补守卫不过（可能已回补过或不变量破坏），留人工核对"
                    + " recordId={} awardId={} quantity={}", order.getRecordId(), order.getAwardId(), order.getQuantity());
        }
    }

    /** 不可重放的活动口径：查无进行中活动 / 已结束 / 未开始（4301/4302）→ 走回补；其余异常按可重试 */
    private boolean isActivityNotReplayable(AppException e) {
        return ResponseCode.LOTTERY_ACTIVITY_ENDED.getCode().equals(e.getCode())
                || ResponseCode.LOTTERY_ACTIVITY_NOT_STARTED.getCode().equals(e.getCode());
    }

    /** 第 retryCount+1 次尝试的退避档下标（封顶最后一档 16 分钟） */
    private LocalDateTime nextBackoffAt(Integer retryCount) {
        int index = Math.min(retryCount == null ? 0 : retryCount, COMPENSATE_BACKOFF.size() - 1);
        return LocalDateTime.now().plus(COMPENSATE_BACKOFF.get(Math.max(index, 0)));
    }
}
