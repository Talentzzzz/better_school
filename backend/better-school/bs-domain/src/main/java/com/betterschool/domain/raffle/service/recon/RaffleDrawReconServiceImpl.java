package com.betterschool.domain.raffle.service.recon;

import com.betterschool.domain.raffle.adapter.port.alert.IDrawReconAlertPort;
import com.betterschool.domain.raffle.adapter.port.recon.IDrawReconQueryPort;
import com.betterschool.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.domain.raffle.model.entity.DrawOrphanPrereserveOrderEntity;
import com.betterschool.domain.raffle.model.entity.DrawReconRoundEntity;
import com.betterschool.domain.raffle.model.entity.DrawReconVarianceEntity;
import com.betterschool.domain.raffle.model.valobj.InventoryLedgerConservationVO;
import com.betterschool.domain.raffle.model.valobj.InventoryPrereserveConservationVO;
import com.betterschool.domain.raffle.model.valobj.RecordMissingDeductVO;
import com.betterschool.domain.raffle.model.valobj.UnmatchedDeductDetailVO;
import com.betterschool.domain.raffle.service.orphan.IRaffleOrphanPrereserveService;
import com.betterschool.types.common.DrawReconAlertMessage;
import com.betterschool.types.raffle.DrawOrphanPrereserveState;
import com.betterschool.types.raffle.DrawReconCheckType;
import com.betterschool.types.raffle.DrawReconVarianceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 抽奖库存全量对账轮实现：只做编排与口径判断——本类不注入 {@code TransactionTemplate}，
 * 每一步都是仓储/端口自带的原子用例（差错登记 INSERT IGNORE、状态推进按起点状态的条件更新），
 * 幂等由差错表 uk_round_check_biz + 轮次表 uk_round_no 承担，调度重复触发 / 同日重跑零副作用。
 * <p>
 * 纯旁路：业务表只读（四条不变量经 {@link IDrawReconQueryPort}、孤儿对账单产物经仓储读面），
 * 写面仅差错表/轮次表；守恒破坏（B1/B3）与反向缺失只告警绝不自动修，
 * 正向漏结复用 {@link IRaffleOrphanPrereserveService#seedOrphanPrereserves} 现有种单入口。
 * 一轮五段：起轮 → 轧账登记（PENDING）→ DEAD 孤儿单点名（登记即 CONFIRMED + 直接告警）
 * → 二次核对（复查全部 PENDING 不分轮次：仍在 → CONFIRMED 并分流，消失 → FALSE_ALARM）→ 终轮回填。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleDrawReconServiceImpl implements IRaffleDrawReconService {

    /**
     * 对账数据窗口：只取 {@code update_time < now - 10min} 的行，与孤儿种单阈值对齐，
     * 避开主链路在途窗口；边界两侧快照不一致的假差错由 PENDING → 下一轮复查消化。
     */
    static final Duration DATA_WINDOW = Duration.ofMinutes(10);

    /** 批大小口径（照 {@code OrphanPrereserveScanJob.SCAN_LIMIT}）：种单入口与 DEAD 点名扫描的上限 */
    static final int SCAN_LIMIT = 100;

    /** 四条不变量按台账账户分批跑的批大小（award_id 分页口径，与孤儿扫描 {@link #SCAN_LIMIT} 同为 100；spec US14） */
    static final int AWARD_BATCH_SIZE = 100;

    private final IRaffleRepository raffleRepository;
    private final IDrawReconQueryPort drawReconQueryPort;
    private final IDrawReconAlertPort drawReconAlertPort;
    private final IRaffleOrphanPrereserveService raffleOrphanPrereserveService;

    @Override
    public int scan(String roundNo) {
        // 段一：起轮（撞 uk_round_no 幂等跳过，同日重跑零副作用）
        LocalDateTime now = LocalDateTime.now();
        DrawReconRoundEntity roundEntity = new DrawReconRoundEntity();
        roundEntity.setRoundNo(roundNo);
        roundEntity.setScanStartTime(now);
        roundEntity.setCreateTime(now);
        roundEntity.setUpdateTime(now);
        raffleRepository.insertReconRound(roundEntity);

        // 段二：轧账——按台账账户分页（批大小 100）逐批跑四条不变量（批内 award_id / inv_id IN 过滤，
        // 全部只读），批间差异行合并成一份清单，四批跑完后统一登记 PENDING（快照带期望/实际读数）
        LocalDateTime before = now.minus(DATA_WINDOW);
        List<DrawReconVarianceEntity> varianceDrafts = new ArrayList<>();
        String cursorAwardId = "";
        while (true) {
            List<String> awardIdBatch = drawReconQueryPort.listLedgerAwardIds(cursorAwardId, AWARD_BATCH_SIZE);
            if (awardIdBatch.isEmpty()) {
                break;
            }
            cursorAwardId = awardIdBatch.get(awardIdBatch.size() - 1);
            collectLedgerConservation(roundNo, before, awardIdBatch, varianceDrafts);
            collectPrereserveConservation(roundNo, before, awardIdBatch, varianceDrafts);
            collectForwardPairing(roundNo, before, awardIdBatch, varianceDrafts);
            collectReversePairing(roundNo, before, awardIdBatch, varianceDrafts);
            if (awardIdBatch.size() < AWARD_BATCH_SIZE) {
                break;
            }
        }
        // 本轮轧账出现过的 (检查类型, 业务单号) 全集——二次核对「同键仍在」的判据；
        // 重跑时撞 uk 被幂等跳过的键也算出现（比对对象是轧账结果，不是插入结果）
        Set<String> currentKeys = new HashSet<>();
        int registered = registerVarianceDrafts(varianceDrafts, currentKeys);

        // 段三：DEAD 孤儿单点名（死信是已确认事实，不走二次核对）
        int patrolled = patrolDeadOrphanOrders(roundNo);

        // 段四：二次核对与平账分流（复查全部轮次的 PENDING，不分轮次）
        int processed = recheckPendingVariances(roundNo, currentKeys);

        // 段五：终轮回填（一轮一行无状态机，重跑按计数覆盖无害）
        int varianceTotal = (int) raffleRepository.countReconVariancesByRound(roundNo);
        int confirmedTotal = (int) raffleRepository
                .countReconVariancesByRoundAndState(roundNo, DrawReconVarianceState.CONFIRMED);
        int autoFixedTotal = (int) raffleRepository
                .countReconVariancesByRoundAndState(roundNo, DrawReconVarianceState.AUTO_FIXED);
        raffleRepository.finishReconRound(roundNo, LocalDateTime.now(),
                varianceTotal, confirmedTotal, autoFixedTotal);

        log.info("[DrawRecon] 全量对账轮完成 roundNo={} 新登记PENDING={} DEAD点名={} 二次核对处理={} 轮次计数(total={}, confirmed={}, autoFixed={})",
                roundNo, registered, patrolled, processed, varianceTotal, confirmedTotal, autoFixedTotal);
        return processed + patrolled;
    }

    // ---------------------------------------------------------------- 段二：轧账登记

    /**
     * B1 台账守恒：限量户（surplus != -1）比 {@code surplus + waiting + issued} 与 Σ分配转入；
     * 不限量户跳过 surplus 项；VIRTUAL 行 waiting 恒 0 公式自然成立（VO javadoc 已注明）。
     * 差异判断在 Java 侧（SQL 不做 CASE 判平），按 award_id 定位：scope_key = biz_id = award_id。
     * 批内按账户 award_id IN 过滤（账户分页口径，spec US14）。
     */
    private void collectLedgerConservation(String roundNo, LocalDateTime before, List<String> awardIdBatch,
                                           List<DrawReconVarianceEntity> varianceDrafts) {
        for (InventoryLedgerConservationVO conservationVO : drawReconQueryPort.queryLedgerConservation(before, awardIdBatch)) {
            boolean limited = conservationVO.getSurplusQty() != null && conservationVO.getSurplusQty() != -1;
            long expected = limited
                    ? conservationVO.getSurplusQty() + nullToZero(conservationVO.getWaitingQty())
                            + nullToZero(conservationVO.getIssuedQty())
                    : nullToZero(conservationVO.getWaitingQty()) + nullToZero(conservationVO.getIssuedQty());
            long actual = nullToZero(conservationVO.getAllocatedQty());
            if (expected == actual) {
                continue;
            }
            String expectSnapshot = limited
                    ? String.format("surplus(%d)+waiting(%d)+issued(%d)=%d", conservationVO.getSurplusQty(),
                            nullToZero(conservationVO.getWaitingQty()), nullToZero(conservationVO.getIssuedQty()), expected)
                    : String.format("waiting(%d)+issued(%d)=%d（不限量户跳过 surplus）",
                            nullToZero(conservationVO.getWaitingQty()), nullToZero(conservationVO.getIssuedQty()), expected);
            varianceDrafts.add(newVarianceEntity(roundNo, DrawReconVarianceState.PENDING,
                    DrawReconCheckType.B1_CONSERVATION, conservationVO.getAwardId(), conservationVO.getAwardId(),
                    expectSnapshot, String.format("Σ(flow_type=0 分配转入明细)=%d", actual)));
        }
    }

    /**
     * B3 预扣守恒：{@code waiting + issued} 与 Σ(flow_type=1 预扣明细)，按 award_id 定位；
     * 批内按账户 award_id IN 过滤（账户分页口径，spec US14）。
     */
    private void collectPrereserveConservation(String roundNo, LocalDateTime before, List<String> awardIdBatch,
                                               List<DrawReconVarianceEntity> varianceDrafts) {
        for (InventoryPrereserveConservationVO conservationVO : drawReconQueryPort
                .queryPrereserveConservation(before, awardIdBatch)) {
            long expected = nullToZero(conservationVO.getWaitingQty()) + nullToZero(conservationVO.getIssuedQty());
            long actual = nullToZero(conservationVO.getDeductedQty());
            if (expected == actual) {
                continue;
            }
            varianceDrafts.add(newVarianceEntity(roundNo, DrawReconVarianceState.PENDING,
                    DrawReconCheckType.B3_CONSERVATION, conservationVO.getAwardId(), conservationVO.getAwardId(),
                    String.format("waiting(%d)+issued(%d)=%d", nullToZero(conservationVO.getWaitingQty()),
                            nullToZero(conservationVO.getIssuedQty()), expected),
                    String.format("Σ(flow_type=1 预扣明细)=%d", actual)));
        }
    }

    /**
     * 正向配对：返回行即漏结候选，scope_key = award_id、biz_id = record_id；
     * 批内按账户 award_id IN 过滤（账户分页口径，spec US14）。
     */
    private void collectForwardPairing(String roundNo, LocalDateTime before, List<String> awardIdBatch,
                                       List<DrawReconVarianceEntity> varianceDrafts) {
        for (UnmatchedDeductDetailVO detailVO : drawReconQueryPort.listUnmatchedDeductDetails(before, awardIdBatch)) {
            varianceDrafts.add(newVarianceEntity(roundNo, DrawReconVarianceState.PENDING,
                    DrawReconCheckType.FORWARD_PAIRING, detailVO.getAwardId(), detailVO.getBizId(),
                    String.format("draw_record 或孤儿对账单命中 record_id=%s（预扣 quantity=%d）",
                            detailVO.getBizId(), nullToZero(detailVO.getQuantity())),
                    "两侧均无命中：记录未落库且对账单未种（种单环节漏了）"));
        }
    }

    /**
     * 反向配对：返回行即数据破坏级缺失候选，scope_key = inv_id（发放来源快照）、biz_id = record_id；
     * 批内按 inv_id IN 过滤（发放来源快照口径不变，spec US14）。
     */
    private void collectReversePairing(String roundNo, LocalDateTime before, List<String> awardIdBatch,
                                       List<DrawReconVarianceEntity> varianceDrafts) {
        for (RecordMissingDeductVO missingVO : drawReconQueryPort.listRecordsMissingDeduct(before, awardIdBatch)) {
            varianceDrafts.add(newVarianceEntity(roundNo, DrawReconVarianceState.PENDING,
                    DrawReconCheckType.REVERSE_PAIRING, missingVO.getInvId(), missingVO.getRecordId(),
                    String.format("预扣明细 award_id=%s biz_id=%s 存在", missingVO.getInvId(), missingVO.getRecordId()),
                    String.format("明细缺失（userId=%s）：记录在、预扣明细丢", missingVO.getUserId())));
        }
    }

    /** 统一登记（四批差异行合并后一次走）：INSERT IGNORE 撞 uk_round_check_biz 静默跳过；键恒计入本轮轧账结果集 */
    private int registerVarianceDrafts(List<DrawReconVarianceEntity> varianceDrafts, Set<String> currentKeys) {
        int registered = 0;
        for (DrawReconVarianceEntity varianceEntity : varianceDrafts) {
            currentKeys.add(varianceKey(varianceEntity.getCheckType(), varianceEntity.getBizId()));
            if (raffleRepository.insertReconVariance(varianceEntity)) {
                log.info("[DrawRecon] 登记差错（待下轮复查确认）roundNo={} checkType={} scopeKey={} bizId={} 期望[{}] 实际[{}]",
                        varianceEntity.getRoundNo(), varianceEntity.getCheckType(), varianceEntity.getScopeKey(),
                        varianceEntity.getBizId(), varianceEntity.getExpectSnapshot(), varianceEntity.getActualSnapshot());
                registered++;
            }
        }
        return registered;
    }

    // ---------------------------------------------------------------- 段三：DEAD 孤儿单点名

    /**
     * DEAD 点名不走二次核对：死信是逐笔链路重试耗尽后的已确认事实（不存在日切误报），
     * 二次核对只会把「点名」推迟一轮而无任何收益——直接登记 CONFIRMED + 告警。
     * 每轮新轮次号 → 每轮一条新差错行 → 周期性点名（dedupKey 按轮次不同，不重复打扰也持续可见）；
     * 人工把死信处置（置回 ORPHAN 或 DONE）后下轮自然消停。
     */
    private int patrolDeadOrphanOrders(String roundNo) {
        int patrolled = 0;
        for (DrawOrphanPrereserveOrderEntity orderEntity : raffleRepository.listDeadOrphanPrereserveOrders(SCAN_LIMIT)) {
            String expectSnapshot = "孤儿对账单收敛完成（state=DONE）";
            String actualSnapshot = String.format("state=DEAD（重试耗尽，retryCount=%s），需人工介入",
                    orderEntity.getRetryCount());
            if (!registerVariance(roundNo, DrawReconVarianceState.CONFIRMED, DrawReconCheckType.DEAD_ORPHAN_PATROL,
                    orderEntity.getRecordId(), orderEntity.getRecordId(), expectSnapshot, actualSnapshot)) {
                continue;
            }
            sendAlert(roundNo, DrawReconCheckType.DEAD_ORPHAN_PATROL,
                    orderEntity.getRecordId(), orderEntity.getRecordId(), expectSnapshot, actualSnapshot);
            patrolled++;
        }
        return patrolled;
    }

    /** 单条登记（INSERT IGNORE 幂等，撞 uk_round_check_biz 返回 false）：轧账走 PENDING、DEAD 点名登记即 CONFIRMED */
    private boolean registerVariance(String roundNo, DrawReconVarianceState state, DrawReconCheckType checkType,
                                     String scopeKey, String bizId, String expectSnapshot, String actualSnapshot) {
        DrawReconVarianceEntity varianceEntity = newVarianceEntity(roundNo, state, checkType, scopeKey, bizId,
                expectSnapshot, actualSnapshot);
        if (!raffleRepository.insertReconVariance(varianceEntity)) {
            return false;
        }
        if (state == DrawReconVarianceState.CONFIRMED) {
            log.warn("[DrawRecon] DEAD 孤儿单点名 roundNo={} recordId={} {}", roundNo, bizId, actualSnapshot);
        } else {
            log.info("[DrawRecon] 登记差错（待下轮复查确认）roundNo={} checkType={} scopeKey={} bizId={} 期望[{}] 实际[{}]",
                    roundNo, checkType, scopeKey, bizId, expectSnapshot, actualSnapshot);
        }
        return true;
    }

    /** 差错行构造（登记共用）：状态由调用方给——轧账 PENDING、DEAD 点名 CONFIRMED */
    private DrawReconVarianceEntity newVarianceEntity(String roundNo, DrawReconVarianceState state,
                                                      DrawReconCheckType checkType, String scopeKey, String bizId,
                                                      String expectSnapshot, String actualSnapshot) {
        LocalDateTime now = LocalDateTime.now();
        DrawReconVarianceEntity varianceEntity = new DrawReconVarianceEntity();
        varianceEntity.setRoundNo(roundNo);
        varianceEntity.setCheckType(checkType);
        varianceEntity.setScopeKey(scopeKey);
        varianceEntity.setBizId(bizId);
        varianceEntity.setExpectSnapshot(expectSnapshot);
        varianceEntity.setActualSnapshot(actualSnapshot);
        varianceEntity.setState(state);
        varianceEntity.setCreateTime(now);
        varianceEntity.setUpdateTime(now);
        return varianceEntity;
    }

    // ---------------------------------------------------------------- 段四：二次核对与平账分流

    /**
     * 复查全部轮次的 PENDING 差错（不分轮次——调度中断停跑多日后，更早轮次的滞留 PENDING
     * 也能收口，登记轮次保留在差错行快照与日志里供人工溯源）：本轮轧账同 (check_type, biz_id)
     * 仍在 → 确认（条件更新 0→1）并按分流表处置；未再出现 → FALSE_ALARM（日切边界假差错收口）。
     * DEAD 点名行登记即 CONFIRMED，不进本复查。
     */
    private int recheckPendingVariances(String roundNo, Set<String> currentKeys) {
        int processed = 0;
        for (DrawReconVarianceEntity varianceEntity : raffleRepository.listPendingReconVariances()) {
            if (!currentKeys.contains(varianceKey(varianceEntity.getCheckType(), varianceEntity.getBizId()))) {
                // 复查消失：取数时刻两侧快照不一致的假差错
                raffleRepository.markReconVarianceFalseAlarm(varianceEntity.getId());
                log.info("[DrawRecon] 复查证伪（假差错收口）登记轮次={} checkType={} bizId={}",
                        varianceEntity.getRoundNo(), varianceEntity.getCheckType(), varianceEntity.getBizId());
                processed++;
                continue;
            }
            // 条件更新 0→1：false = 已被并发推进/证伪，跳过
            if (!raffleRepository.confirmReconVariance(varianceEntity.getId())) {
                continue;
            }
            routeConfirmed(roundNo, varianceEntity);
            processed++;
        }
        return processed;
    }

    /** 平账分流（CONFIRMED 后，照 spec 分流表） */
    private void routeConfirmed(String roundNo, DrawReconVarianceEntity varianceEntity) {
        if (varianceEntity.getCheckType() == DrawReconCheckType.FORWARD_PAIRING) {
            routeForwardPairing(roundNo, varianceEntity);
            return;
        }
        // B1/B3 守恒破坏与反向缺失 = 数据破坏级：只告警人工，绝不自动修；
        // 差错保持 CONFIRMED，人工核对后置 RESOLVED_MANUAL
        sendAlert(roundNo, varianceEntity.getCheckType(), varianceEntity.getScopeKey(), varianceEntity.getBizId(),
                varianceEntity.getExpectSnapshot(), varianceEntity.getActualSnapshot());
        log.error("[DrawRecon] 确认数据破坏级差异（只告警不自动修，等人工核对）roundNo={} checkType={} scopeKey={} bizId={} 期望[{}] 实际[{}]",
                roundNo, varianceEntity.getCheckType(), varianceEntity.getScopeKey(), varianceEntity.getBizId(),
                varianceEntity.getExpectSnapshot(), varianceEntity.getActualSnapshot());
    }

    /**
     * 正向漏结分流：查无对账单 → 复用现有孤儿种单入口补种（扫描本身以 NOT EXISTS + 10 分钟阈值
     * 圈定候选，INSERT IGNORE 幂等，不复制种单逻辑）；补种后按 record_id 回查对账单——
     * 种单扫描有 limit 截断且候选可能被跳过（缺扣次明细留 ERROR），特定单未必真种上：
     * 回查命中才置 AUTO_FIXED 关单，未命中保持 CONFIRMED + 告警人工（虚假关单的差错不再复查）。
     * 孤儿单已 DEAD → 告警人工；对账单在途（ORPHAN）/已收敛（DONE）→ 逐笔收敛链路已接管或闭环，
     * 保持 CONFIRMED 留观测。
     */
    private void routeForwardPairing(String roundNo, DrawReconVarianceEntity varianceEntity) {
        String recordId = varianceEntity.getBizId();
        DrawOrphanPrereserveOrderEntity orderEntity = raffleRepository.queryOrphanPrereserveOrderByRecordId(recordId);
        if (orderEntity == null) {
            raffleOrphanPrereserveService.seedOrphanPrereserves(SCAN_LIMIT);
            DrawOrphanPrereserveOrderEntity seededEntity = raffleRepository.queryOrphanPrereserveOrderByRecordId(recordId);
            if (seededEntity != null) {
                raffleRepository.markReconVarianceAutoFixed(varianceEntity.getId());
                log.info("[DrawRecon] 正向漏结复用孤儿种单入口补种，置 AUTO_FIXED roundNo={} recordId={} awardId={}",
                        roundNo, recordId, varianceEntity.getScopeKey());
                return;
            }
            sendAlert(roundNo, varianceEntity.getCheckType(), varianceEntity.getScopeKey(), recordId,
                    varianceEntity.getExpectSnapshot(),
                    "补种扫描未命中该单（不在候选序或候选被跳过，如缺扣次明细），保持 CONFIRMED，需人工核对补种");
            return;
        }
        if (orderEntity.getState() == DrawOrphanPrereserveState.DEAD) {
            sendAlert(roundNo, varianceEntity.getCheckType(), varianceEntity.getScopeKey(), recordId,
                    varianceEntity.getExpectSnapshot(),
                    "对应孤儿对账单已进死信（state=DEAD），逐笔链路救不回，需人工介入");
            return;
        }
        log.info("[DrawRecon] 正向漏结的孤儿对账单已在途/已收敛（state={}），逐笔链路接管，保持 CONFIRMED recordId={}",
                orderEntity.getState(), recordId);
    }

    // ---------------------------------------------------------------- 公共小件

    /** 发告警（经端口 outbox → RocketMQ → 单体消费落通知）：投递失败不中断本轮轧账，下轮重新点名 */
    private void sendAlert(String roundNo, DrawReconCheckType checkType, String scopeKey, String bizId,
                           String expectSummary, String actualSummary) {
        String alertText = String.format(
                "抽奖库存对账告警：轮次 %s 检查[%s]确认差异，scopeKey=%s bizId=%s；期望：%s；实际：%s。请人工核对，处置后在差错表收口。",
                roundNo, checkType.getDesc(), scopeKey, bizId, expectSummary, actualSummary);
        DrawReconAlertMessage alertMessage = new DrawReconAlertMessage(roundNo, checkType.name(), scopeKey,
                bizId, expectSummary, actualSummary, alertText);
        try {
            drawReconAlertPort.sendReconAlert(alertMessage);
        } catch (Exception e) {
            log.error("[DrawRecon] 告警投递失败（差错已登记，下一轮对账将重新点名）roundNo={} checkType={} bizId={}",
                    roundNo, checkType, bizId, e);
        }
    }

    /** 二次核对的「同键仍在」判据：检查类型 + 业务单号（与 uk_round_check_biz 后两位一致） */
    private String varianceKey(DrawReconCheckType checkType, String bizId) {
        return checkType.name() + "|" + bizId;
    }

    /** 聚合读数空值兜底（COALESCE 已保证 SUM 侧非空，台账三列 NOT NULL，防御性取 0） */
    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
