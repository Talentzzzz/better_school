package com.betterschool.marketing.domain.inventory.service;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.service.merge.InventoryMerger;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 库存配额实现（award-inventory 两层模型）：实物/虚拟共用一套骨架，唯一的类型相关差异都在仓储里
 * （统一台账按 {@code type} 分支两条 UPDATE）。预扣的账户维度是 {@code awardId}（奖品位 id），
 * 池只被「分配」转移（见 {@code InventoryAllocationServiceImpl}），抽奖链路永远不碰池。
 * <pre>
 * 预扣：查奖品库存台账（不存在 → INVENTORY_PARAM_ERROR；非 open → 走重放预检）
 *       → 进进程内合并器等结果（一组一事务：明细 INSERT IGNORE + 台账一条 UPDATE），
 *         成功 = 已提交，返回时台账已动，没有第二步
 *       → 缓冲满 / 停机中：调用方线程同步扣一批（组大小 1，复用同一个落库方法）
 * 确认：awardId 空（不限量档）短路为「无事可做」
 *       → 进同一个合并器的确认重载（一组一事务：flow_type = 2 明细逐行 INSERT IGNORE +
 *         waiting→issued 一条 UPDATE），成功 = 转移已提交，返回即落账，没有第二步
 *       → 缓冲满 / 停机中：调用方线程同步确认一件（组大小 1，复用同一个落库方法）
 * </pre>
 * 业务身份：明细带 {@code bizType} 与 {@code bizId = recordId}，唯一键保证同一笔业务在同一账户上
 * 只落一行。**撞键不是错误**——撞键 = 这条流转已落过（同 token 并发双击的第二笔），落库方法按
 * 「成功回放」处理；主档与兜底是两个账户（两个 awardId），互不撞键。
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
    private final InventoryMerger inventoryMerger;

    @Override
    public boolean preDeduct(AwardInventoryType type, String awardId, long quantity, String bizType, String bizId) {
        InventoryLedgerVO ledger = inventoryRepository.queryAwardInventory(awardId);
        if (ledger == null) {
            throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR.getCode(),
                    "奖品库存账户不存在 type=" + type.getCode() + " awardId=" + awardId);
        }
        if (!ledger.isOpen()) {
            // 非 open 只可能 exhausted / closed：重放命中按成功回放，其余按余量不足拒绝
            return alreadyDeducted(awardId, bizType, bizId);
        }
        InventoryDeductRequestVO request = new InventoryDeductRequestVO(type, awardId, quantity, bizType, bizId);
        DeductResult result = inventoryMerger.submitAndWait(request);
        return switch (result) {
            case DEDUCTED -> {
                log.info("奖品库存预扣已提交 type={} awardId={} quantity={} bizType={} bizId={}",
                        type.getCode(), awardId, quantity, bizType, bizId);
                yield true;
            }
            case INSUFFICIENT -> {
                log.info("奖品库存预扣余量不足 type={} awardId={} quantity={} bizType={} bizId={}",
                        type.getCode(), awardId, quantity, bizType, bizId);
                yield false;
            }
            // 还在缓冲 / 落库中，大概率会扣：不切兜底、不落库，抛可重试（用户凭同 token 重放）
            case TIMEOUT_UNKNOWN, FAILED -> throw new AppException(ResponseCode.SYSTEM_ERROR,
                    "库存处理中，请稍后重试 type=" + type.getCode() + " awardId=" + awardId
                            + " bizId=" + bizId + " result=" + result);
            // 件未入队：调用方线程同步扣一批（组大小 1），复用同一个落库方法（spec §2.6）
            case BUFFER_FULL, CLOSED -> degradeFlush(type, awardId, request);
        };
    }

    @Override
    public DeductOutcomeVO preDeductWithFallback(InventoryDeductRequestVO primary,
                                                 InventoryDeductRequestVO fallback) {
        if (preDeduct(primary.type(), primary.awardId(), primary.quantity(), primary.bizType(), primary.bizId())) {
            return DeductOutcomeVO.DEDUCTED;
        }
        if (fallback == null) {
            return DeductOutcomeVO.FAILED;
        }
        // 主档扣不出：若上一笔同业务走的是兜底账户，按 FALLBACK 回放，不再扣第二次。
        // 主档与兜底是同一张明细表上两个 awardId 前缀的账户，唯一键互不撞——按兜底账户的键查即可。
        if (alreadyDeducted(fallback.awardId(), fallback.bizType(), fallback.bizId())) {
            log.info("主档扣不出，但兜底账户明细已存在，按成功回放 type={} awardId={} bizId={}",
                    fallback.type().getCode(), fallback.awardId(), fallback.bizId());
            return DeductOutcomeVO.FALLBACK;
        }
        // 主档不足（业务事实）：改落兜底账户。TIMEOUT_UNKNOWN / 落库失败已在 preDeduct 里抛出，不会走到这
        if (preDeduct(fallback.type(), fallback.awardId(), fallback.quantity(),
                fallback.bizType(), fallback.bizId())) {
            log.info("主档不足，已落兜底 type={} awardId={} fallbackType={} fallbackAwardId={}"
                            + " quantity={} bizId={}",
                    primary.type().getCode(), primary.awardId(), fallback.type().getCode(),
                    fallback.awardId(), fallback.quantity(), fallback.bizId());
            return DeductOutcomeVO.FALLBACK;
        }
        log.warn("主档与兜底均不足 type={} awardId={} fallbackType={} fallbackAwardId={}"
                        + " quantity={} bizId={}",
                primary.type().getCode(), primary.awardId(), fallback.type().getCode(),
                fallback.awardId(), fallback.quantity(), fallback.bizId());
        return DeductOutcomeVO.FAILED;
    }

    /**
     * 兑换确认（同步合并，spec §4.5）：进与预扣同一个 {@code InventoryMerger}（同分片）的
     * 确认重载，落库是「{@code flow_type = 2} 明细逐行 {@code INSERT IGNORE} + {@code waiting → issued}
     * 一条 {@code UPDATE}」的另一组（与预扣不合成一条三列 {@code UPDATE}——两个失败域不能混在一句里）。
     * 返回即 {@code waiting -= c, issued += c} 已提交，**没有第二步**。
     * <p>
     * {@code awardId} 空（不限量档，{@code draw_record.inv_id} 落 {@code NULL}）先短路为
     * 「无事可做」，不与「账不平」共用异常。
     * <p>
     * 幂等：同一条记录重复确认会撞 {@code uk_award_biz (award_id, biz_type, biz_id)}——撞键 = 已确认过
     * = 按成功回放，不重复转移（裁决在落库方法内由 {@code INSERT IGNORE} 完成）。
     * <p>
     * {@code waiting} 不足 = 不变量破坏（不是业务失败）：整组回滚（连确认明细一起）+ error 日志
     * + 抛可重试 ⇒ MQ 重投 ⇒ 死信。不复活 {@code INVENTORY_WAITING_SHORTAGE(4312)}，不做部分确认。
     */
    @Override
    public void confirmIssued(String awardId, String bizType, String bizId, long quantity) {
        if (awardId == null || awardId.isBlank()) {
            // 不限量档没有库存账户可转移：无事可做（与「账不平」是两个出口，spec §4.5）
            log.info("兑换确认无事可做（不限量档，无库存账户） bizType={} bizId={} quantity={}",
                    bizType, bizId, quantity);
            return;
        }
        InventoryClaimRequestVO request = new InventoryClaimRequestVO(awardId, bizType, bizId, quantity);
        ClaimOutcome outcome = inventoryMerger.submitAndWait(request);
        switch (outcome) {
            case CONFIRMED -> log.info("兑换确认已提交（waiting -= c, issued += c 随返回生效）"
                    + " awardId={} bizType={} bizId={} quantity={}", awardId, bizType, bizId, quantity);
            // 还在缓冲 / 落库中，或整组回滚：件可能已落 ⇒ 抛可重试，MQ 重投撞唯一键回放或补上
            case TIMEOUT_UNKNOWN, FAILED -> throw new AppException(ResponseCode.SYSTEM_ERROR,
                    "库存确认处理中，请稍后重试 awardId=" + awardId + " bizType=" + bizType
                            + " bizId=" + bizId + " outcome=" + outcome);
            // 件未入队：调用方线程同步确认一件（组大小 1），复用同一个落库方法（spec §2.6）
            case BUFFER_FULL, CLOSED -> degradeConfirm(request);
        }
    }

    /**
     * 降级：合并器缓冲满 / 停机中，本线程同步确认一件（组大小 1）。
     * **复用同一个落库方法**（{@code flushClaimGroup}）——两套 SQL 迟早漂移，而漂移只在压力最大时暴露
     * （spec §2.6）。落库事务失败（含 waiting 不足的整组回滚）原样抛给调用方 ⇒ MQ 重投。
     */
    private void degradeConfirm(InventoryClaimRequestVO request) {
        log.warn("合并器缓冲满或已停机，降级为同步确认（组大小 1） awardId={} bizId={}",
                request.awardId(), request.bizId());
        List<ClaimOutcome> results = inventoryRepository.flushClaimGroup(List.of(request));
        if (results.size() != 1 || results.get(0) != ClaimOutcome.CONFIRMED) {
            // 落库方法的契约是「返回即 CONFIRMED、失败即抛」，这里只是把漏回填也当失败兜住
            throw new AppException(ResponseCode.SYSTEM_ERROR,
                    "库存确认降级落库未确认（可重试） awardId=" + awardId + " bizId=" + item.bizId());
        }
    }

    /**
     * 降级：合并器缓冲满 / 停机中，本线程同步扣一批（组大小 1）。
     * **复用同一个落库方法**（{@code flushDeductGroup}）——两套 SQL 迟早漂移，而漂移只在压力最大时暴露
     * （spec §2.6）。落库事务失败原样抛给调用方（ERROR，凭 bizId 重试不双扣）。
     */
    private boolean degradeFlush(AwardInventoryType type, String awardId, InventoryDeductRequestVO request) {
        log.warn("合并器缓冲满或已停机，降级为同步落库（组大小 1） type={} awardId={} bizId={}",
                type.getCode(), awardId, request.bizId());
        List<DeductResult> results =
                inventoryRepository.flushDeductGroup(type, awardId, List.of(request));
        return results.get(0) == DeductResult.DEDUCTED;
    }

    /**
     * 本账户扣不出时的业务幂等预检：这场流转在本账户上已经落过明细 = 重放，按成功回放。
     * 只在失败路径调用（成功路径靠唯一键撞键，不多付一次查询）——覆盖
     * 「台账已 exhausted / closed，压根进不了合并器」窗口内的同 {@code bizId} 重放。
     */
    private boolean alreadyDeducted(String awardId, String bizType, String bizId) {
        if (!inventoryRepository.existsDeductDetail(awardId, bizType, bizId)) {
            return false;
        }
        log.info("库存预扣扣不出，但本账户的业务明细已存在，按成功回放 awardId={} bizType={} bizId={}",
                awardId, bizType, bizId);
        return true;
    }
}
