package com.betterschool.marketing.domain.inventory.service;

import com.betterschool.marketing.domain.inventory.adapter.port.IQuotaMergePort;
import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 库存配额实现：两段共用一套骨架，唯一的段相关差异都在仓储里（目标表 / 明细多一列 {@code type}）。
 * <pre>
 * 预扣（票 06）：查台账（不存在 → INVENTORY_PARAM_ERROR；非 open → 走重放预检）
 *       → 进进程内合并器等结果（一组一事务：明细 INSERT IGNORE + 台账一条 UPDATE），
 *         成功 = 已提交，返回时台账已动，没有第二步
 *       → 缓冲满 / 停机中：调用方线程同步扣一批（组大小 1，复用同一个落库方法）
 * 确认（票 07）：invId &lt;= 0（不限量档）短路为「无事可做」
 *       → 进同一个合并器的确认重载（一组一事务：type = 2 明细逐行 INSERT IGNORE + waiting→issued
 *         一条 UPDATE），成功 = 转移已提交，返回即落账，没有第二步
 *       → 缓冲满 / 停机中：调用方线程同步确认一件（组大小 1，复用同一个落库方法）
 * </pre>
 * 业务身份：明细带 {@code bizType} 与 {@code bizId = recordId}，唯一键保证同一笔业务只落一行。
 * **撞键不是错误**——撞键 = 这条流转已落过（同 token 并发双击的第二笔），落库方法按「成功回放」处理。
 * <p>
 * 预扣三出口的翻译（spec §4.4）：{@code DEDUCTED → true}；{@code INSUFFICIENT → false}（调用方切兜底）；
 * {@code TIMEOUT_UNKNOWN} / {@code FAILED → 抛可重试}（不切兜底、不落库——切兜底会扣两份库存发两个奖）。
 * 确认只有两条业务出口（spec §4.5）：{@code CONFIRMED → 返回}；
 * {@code TIMEOUT_UNKNOWN} / {@code FAILED → 抛可重试}（MQ 重投撞唯一键回放或补上）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryQuotaServiceImpl implements IInventoryQuotaService {

    private final IInventoryRepository inventoryRepository;
    private final IQuotaMergePort quotaMergePort;

    @Override
    public boolean preDeduct(InventorySegmentVO segment, long invId, long quantity, String bizType, long bizId) {
        InventoryLedgerVO ledger = inventoryRepository.queryLedger(segment, invId);
        if (ledger == null) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(),
                    "库存不存在 segment=" + segment.getCode() + " invId=" + invId);
        }
        if (!ledger.isOpen()) {
            // 非 open 只可能 exhausted / closed：重放命中按成功回放，其余按余量不足拒绝
            return alreadyDeducted(segment, invId, bizType, bizId);
        }
        InventoryDeductRequestVO request = new InventoryDeductRequestVO(segment, invId, quantity, bizType, bizId);
        DeductResult result = quotaMergePort.submitAndWait(invId, request);
        return switch (result) {
            case DEDUCTED -> {
                log.info("库存预扣已提交 segment={} invId={} quantity={} bizType={} bizId={}",
                        segment.getCode(), invId, quantity, bizType, bizId);
                yield true;
            }
            case INSUFFICIENT -> {
                log.info("库存预扣余量不足 segment={} invId={} quantity={} bizType={} bizId={}",
                        segment.getCode(), invId, quantity, bizType, bizId);
                yield false;
            }
            // 还在缓冲 / 落库中，大概率会扣：不切兜底、不落库，抛可重试（用户凭同 token 重放）
            case TIMEOUT_UNKNOWN, FAILED -> throw new AppException(ResponseCode.SYSTEM_ERROR,
                    "库存处理中，请稍后重试 segment=" + segment.getCode() + " invId=" + invId
                            + " bizId=" + bizId + " result=" + result);
            // 件未入队：调用方线程同步扣一批（组大小 1），复用同一个落库方法（spec §2.6）
            case BUFFER_FULL, CLOSED -> degradeFlush(segment, invId, request);
        };
    }

    @Override
    public DeductOutcomeVO preDeductWithFallback(InventoryDeductRequestVO primary,
                                                 InventoryDeductRequestVO fallback) {
        if (preDeduct(primary.segment(), primary.invId(), primary.quantity(), primary.bizType(), primary.bizId())) {
            return DeductOutcomeVO.DEDUCTED;
        }
        if (fallback == null) {
            return DeductOutcomeVO.FAILED;
        }
        // 主段扣不出：若上一笔同业务走的是兜底段，按 FALLBACK 回放，不再扣第二次。
        // 这一步是跨表补的：两张明细表的业务唯一键各管一张表，主档与兜底之间不会互相撞键。
        if (alreadyDeducted(fallback.segment(), fallback.invId(), fallback.bizType(), fallback.bizId())) {
            log.info("主档扣不出，但兜底段明细已存在，按成功回放 segment={} invId={} bizId={}",
                    fallback.segment().getCode(), fallback.invId(), fallback.bizId());
            return DeductOutcomeVO.FALLBACK;
        }
        // 主档不足（业务事实）：改落兜底段。TIMEOUT_UNKNOWN / 落库失败已在 preDeduct 里抛出，不会走到这
        if (preDeduct(fallback.segment(), fallback.invId(), fallback.quantity(),
                fallback.bizType(), fallback.bizId())) {
            log.info("主档不足，已落兜底 segment={} invId={} fallbackSegment={} fallbackInvId={}"
                            + " quantity={} bizId={}",
                    primary.segment().getCode(), primary.invId(), fallback.segment().getCode(),
                    fallback.invId(), fallback.quantity(), fallback.bizId());
            return DeductOutcomeVO.FALLBACK;
        }
        log.warn("主档与兜底均不足 segment={} invId={} fallbackSegment={} fallbackInvId={}"
                        + " quantity={} bizId={}",
                primary.segment().getCode(), primary.invId(), fallback.segment().getCode(),
                fallback.invId(), fallback.quantity(), fallback.bizId());
        return DeductOutcomeVO.FAILED;
    }

    /**
     * 兑换确认（票 07：同步合并，spec §4.5）：进与预扣同一个 {@code QuotaMergeBuffer}（同分片）的
     * 确认重载，落库是「{@code type = 2} 明细逐行 {@code INSERT IGNORE} + {@code waiting → issued}
     * 一条 {@code UPDATE}」的另一组（与预扣不合成一条三列 {@code UPDATE}——两个失败域不能混在一句里）。
     * 返回即 {@code waiting -= c, issued += c} 已提交，**没有第二步**。
     * <p>
     * {@code invId <= 0}（不限量档，{@code draw_record.inv_id} 落 {@code NULL}）先短路为
     * 「无事可做」，不与「账不平」共用异常。
     * <p>
     * 幂等：同一条记录重复确认会撞 {@code uk_biz_type (type, biz_type, biz_id)}——撞键 = 已确认过
     * = 按成功回放，不重复转移（裁决在落库方法内由 {@code INSERT IGNORE} 完成）。
     * <p>
     * {@code waiting} 不足 = 不变量破坏（不是业务失败）：整组回滚（连确认明细一起）+ error 日志
     * + 抛可重试 ⇒ MQ 重投 ⇒ 死信。不复活 {@code INVENTORY_WAITING_SHORTAGE(4312)}，不做部分确认。
     */
    @Override
    public void confirmIssued(long invId, String bizType, long bizId, long quantity) {
        if (invId <= 0) {
            // 不限量档没有实物配额行可转移：无事可做（与「账不平」是两个出口，spec §4.5）
            log.info("兑换确认无事可做（不限量档，无实物配额行） bizType={} bizId={} quantity={}",
                    bizType, bizId, quantity);
            return;
        }
        ClaimItem item = new ClaimItem(bizType, bizId, quantity);
        ClaimOutcome outcome = quotaMergePort.submitAndWait(invId, item);
        switch (outcome) {
            case CONFIRMED -> log.info("兑换确认已提交（waiting -= c, issued += c 随返回生效）"
                    + " invId={} bizType={} bizId={} quantity={}", invId, bizType, bizId, quantity);
            // 还在缓冲 / 落库中，或整组回滚：件可能已落 ⇒ 抛可重试，MQ 重投撞唯一键回放或补上
            case TIMEOUT_UNKNOWN, FAILED -> throw new AppException(ResponseCode.SYSTEM_ERROR,
                    "库存确认处理中，请稍后重试 invId=" + invId + " bizType=" + bizType
                            + " bizId=" + bizId + " outcome=" + outcome);
            // 件未入队：调用方线程同步确认一件（组大小 1），复用同一个落库方法（spec §2.6）
            case BUFFER_FULL, CLOSED -> degradeConfirm(invId, item);
        }
    }

    /**
     * 降级：合并器缓冲满 / 停机中，本线程同步确认一件（组大小 1）。
     * **复用同一个落库方法**（{@code flushClaimGroup}）——两套 SQL 迟早漂移，而漂移只在压力最大时暴露
     * （spec §2.6）。落库事务失败（含 waiting 不足的整组回滚）原样抛给调用方 ⇒ MQ 重投。
     */
    private void degradeConfirm(long invId, ClaimItem item) {
        log.warn("合并器缓冲满或已停机，降级为同步确认（组大小 1） invId={} bizId={}", invId, item.bizId());
        List<ClaimOutcome> results = inventoryRepository.flushClaimGroup(invId, List.of(item));
        if (results.size() != 1 || results.get(0) != ClaimOutcome.CONFIRMED) {
            // 落库方法的契约是「返回即 CONFIRMED、失败即抛」，这里只是把漏回填也当失败兜住
            throw new AppException(ResponseCode.SYSTEM_ERROR,
                    "库存确认降级落库未确认（可重试） invId=" + invId + " bizId=" + item.bizId());
        }
    }

    /**
     * 降级：合并器缓冲满 / 停机中，本线程同步扣一批（组大小 1）。
     * **复用同一个落库方法**（{@code flushDeductGroup}）——两套 SQL 迟早漂移，而漂移只在压力最大时暴露
     * （spec §2.6）。落库事务失败原样抛给调用方（ERROR，凭 bizId 重试不双扣）。
     */
    private boolean degradeFlush(InventorySegmentVO segment, long invId, InventoryDeductRequestVO request) {
        log.warn("合并器缓冲满或已停机，降级为同步落库（组大小 1） segment={} invId={} bizId={}",
                segment.getCode(), invId, request.bizId());
        List<DeductResult> results =
                inventoryRepository.flushDeductGroup(segment, invId, List.of(request));
        return results.get(0) == DeductResult.DEDUCTED;
    }

    /**
     * 本段扣不出时的业务幂等预检：这场流转在本 inv 上已经落过明细 = 重放，按成功回放。
     * 只在失败路径调用（成功路径靠唯一键撞键，不多付一次查询）——覆盖
     * 「台账已 exhausted / closed，压根进不了合并器」窗口内的同 {@code bizId} 重放。
     */
    private boolean alreadyDeducted(InventorySegmentVO segment, long invId, String bizType, long bizId) {
        if (!inventoryRepository.existsDeductDetail(segment, invId, bizType, bizId)) {
            return false;
        }
        log.info("库存预扣扣不出，但本 inv 的业务明细已存在，按成功回放 segment={} invId={} bizType={} bizId={}",
                segment.getCode(), invId, bizType, bizId);
        return true;
    }
}
