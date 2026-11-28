package com.betterschool.domain.award.service;

import com.betterschool.domain.award.adapter.repository.IAwardRepository;
import com.betterschool.domain.award.model.valobj.AwardClaimRequestVO;
import com.betterschool.domain.award.model.valobj.ClaimOutcomeEnum;
import com.betterschool.domain.award.model.valobj.DeductOutcomeEnum;
import com.betterschool.domain.award.model.valobj.DeductResultEnum;
import com.betterschool.domain.award.model.valobj.AwardDeductRequestVO;
import com.betterschool.domain.award.model.valobj.AwardLedgerVO;
import com.betterschool.domain.award.model.valobj.OrphanPrereserveCandidateVO;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.award.AwardInventoryTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 奖品库存实现：实物/虚拟共用一套骨架，唯一的类型相关差异都在仓储里
 * （台账按 {@code type} 分支两条 UPDATE）。预扣的账户维度是 {@code awardId}（奖品位 id），
 * 库存由运营直接维护到账户，不存在配额池与分配链路。
 * <pre>
 * 预扣：查奖品库存台账（不存在 → INVENTORY_PARAM_ERROR；非 open → 走重放预检）
 *       → 仓储扣一件（成功 = 已提交：明细 INSERT IGNORE + 台账一条 UPDATE，返回时台账已动，没有第二步）
 *       → 三条业务出口：DEDUCTED / INSUFFICIENT / RETRY_LATER
 * 确认：awardId 空（不限量档）短路为「无事可做」
 *       → 仓储确认一件（成功 = 转移已提交：flow_type = 2 明细 + waiting→issued 一条 UPDATE）
 *       → 两条业务出口：CONFIRMED / RETRY_LATER
 * </pre>
 * <b>本类不认识「合并」</b>：一件扣减是并进一批落库还是单件落库、合并器忙时怎么降级，
 * 都在仓储实现内部（{@code infrastructure/merge}）。域只回答业务问题——
 * 扣到了没有、确没确认、要不要让调用方重试。
 * <p>
 * 业务身份：明细带 {@code bizType} 与 {@code bizId = recordId}，唯一键保证同一笔业务在同一账户上
 * 只落一行。**撞键不是错误**——撞键 = 这条流转已落过（同 token 并发双击的第二笔），落库方法按
 * 「成功回放」处理；主档与兜底是两个账户（两个 awardId），互不撞键。
 * <p>
 * 预扣三出口的翻译（spec §4.4）：{@code DEDUCTED → true}；{@code INSUFFICIENT → false}（调用方切兜底）；
 * {@code RETRY_LATER → 抛可重试}（不切兜底、不落库——切兜底会扣两份库存发两个奖）。
 * 确认只有两条业务出口（spec §4.5）：{@code CONFIRMED → 返回}；{@code RETRY_LATER → 抛可重试}
 * （MQ 重投撞唯一键回放或补上）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AwardInventoryServiceImpl implements IAwardInventoryService {

    private final IAwardRepository awardRepository;

    @Override
    public boolean preDeduct(AwardInventoryTypeEnum awardInventoryType, String awardId, long quantity, String bizType, String bizId) {
        AwardLedgerVO awardLedgerVO = awardRepository.queryAwardInventory(awardId);
        if (awardLedgerVO == null) {
            throw new AppException(ResponseCodeEnum.INVENTORY_PARAM_ERROR.getCode(),
                    "奖品库存账户不存在 type=" + awardInventoryType.getCode() + " awardId=" + awardId);
        }
        if (!awardLedgerVO.isOpen()) {
            // 非 open 只可能 exhausted / closed：重放命中按成功回放，其余按余量不足拒绝
            return alreadyDeducted(awardId, bizType, bizId);
        }
        AwardDeductRequestVO awardDeductRequestVO = new AwardDeductRequestVO(awardInventoryType, awardId, quantity, bizType, bizId);
        DeductResultEnum deductResult = awardRepository.deduct(awardDeductRequestVO);
        return switch (deductResult) {
            case DEDUCTED -> {
                log.info("奖品库存预扣已提交 type={} awardId={} quantity={} bizType={} bizId={}",
                        awardInventoryType.getCode(), awardId, quantity, bizType, bizId);
                yield true;
            }
            case INSUFFICIENT -> {
                log.info("奖品库存预扣余量不足 type={} awardId={} quantity={} bizType={} bizId={}",
                        awardInventoryType.getCode(), awardId, quantity, bizType, bizId);
                yield false;
            }
            // 还在处理中或已回滚：不切兜底、不落库，抛可重试（用户凭同 token 重放）
            case RETRY_LATER -> throw new AppException(ResponseCodeEnum.SYSTEM_ERROR,
                    "库存处理中，请稍后重试 type=" + awardInventoryType.getCode() + " awardId=" + awardId
                            + " bizId=" + bizId);
        };
    }

    @Override
    public boolean upsertInventory(String awardId, AwardInventoryTypeEnum awardInventoryType,
                                   Long surplusQty, Long valueQty, Integer state) {
        return awardRepository.upsertInventory(awardId, awardInventoryType.name(), surplusQty, valueQty, state);
    }

    @Override
    public DeductOutcomeEnum preDeductWithFallback(AwardDeductRequestVO primary,
                                                 AwardDeductRequestVO fallback) {
        if (preDeduct(primary.type(), primary.awardId(), primary.quantity(), primary.bizType(), primary.bizId())) {
            return DeductOutcomeEnum.DEDUCTED;
        }
        if (fallback == null) {
            return DeductOutcomeEnum.FAILED;
        }
        // 主档扣不出：若上一笔同业务走的是兜底账户，按 FALLBACK 回放，不再扣第二次。
        // 主档与兜底是同一张明细表上两个 awardId 前缀的账户，唯一键互不撞——按兜底账户的键查即可。
        if (alreadyDeducted(fallback.awardId(), fallback.bizType(), fallback.bizId())) {
            log.info("主档扣不出，但兜底账户明细已存在，按成功回放 type={} awardId={} bizId={}",
                    fallback.type().getCode(), fallback.awardId(), fallback.bizId());
            return DeductOutcomeEnum.FALLBACK;
        }
        // 主档不足（业务事实）：改落兜底账户。RETRY_LATER 已在 preDeduct 里抛出，不会走到这
        if (preDeduct(fallback.type(), fallback.awardId(), fallback.quantity(),
                fallback.bizType(), fallback.bizId())) {
            log.info("主档不足，已落兜底 type={} awardId={} fallbackType={} fallbackAwardId={}"
                            + " quantity={} bizId={}",
                    primary.type().getCode(), primary.awardId(), fallback.type().getCode(),
                    fallback.awardId(), fallback.quantity(), fallback.bizId());
            return DeductOutcomeEnum.FALLBACK;
        }
        log.warn("主档与兜底均不足 type={} awardId={} fallbackType={} fallbackAwardId={}"
                        + " quantity={} bizId={}",
                primary.type().getCode(), primary.awardId(), fallback.type().getCode(),
                fallback.awardId(), fallback.quantity(), fallback.bizId());
        return DeductOutcomeEnum.FAILED;
    }

    /**
     * 兑换确认（spec §4.5）：仓储确认一件——{@code flow_type = 2} 明细 {@code INSERT IGNORE} +
     * {@code waiting → issued} 一条 {@code UPDATE}，一个事务。返回即 {@code waiting -= c, issued += c}
     * 已提交，**没有第二步**。
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
        AwardClaimRequestVO awardClaimRequestVO = new AwardClaimRequestVO(awardId, bizType, bizId, quantity);
        ClaimOutcomeEnum claimOutcome = awardRepository.claim(awardClaimRequestVO);
        switch (claimOutcome) {
            case CONFIRMED -> log.info("兑换确认已提交（waiting -= c, issued += c 随返回生效）"
                    + " awardId={} bizType={} bizId={} quantity={}", awardId, bizType, bizId, quantity);
            // 可能已确认、也可能整组回滚：抛可重试，MQ 重投撞唯一键回放或补上
            case RETRY_LATER -> throw new AppException(ResponseCodeEnum.SYSTEM_ERROR,
                    "库存确认处理中，请稍后重试 awardId=" + awardId + " bizType=" + bizType
                            + " bizId=" + bizId);
        }
    }

    /**
     * 本账户扣不出时的业务幂等预检：这场流转在本账户上已经落过明细 = 重放，按成功回放。
     * 只在失败路径调用（成功路径靠唯一键撞键，不多付一次查询）——覆盖
     * 「台账已 exhausted / closed，压根进不了落库」窗口内的同 {@code bizId} 重放。
     */
    private boolean alreadyDeducted(String awardId, String bizType, String bizId) {
        if (!awardRepository.existsDeductDetail(awardId, bizType, bizId)) {
            return false;
        }
        log.info("库存预扣扣不出，但本账户的业务明细已存在，按成功回放 awardId={} bizType={} bizId={}",
                awardId, bizType, bizId);
        return true;
    }

    // ---- 孤儿预扣对账（spec User Stories 14–16；低频对账动作，不进合并） ----

    @Override
    public List<OrphanPrereserveCandidateVO> listOrphanPrereserves(LocalDateTime before, int limit) {
        return awardRepository.listOrphanPrereserveDetails(before, limit);
    }

    /**
     * 回补一笔孤儿预扣：低频直落（对账扫描每轮至多 {@code limit} 行，不值得并批），
     * 台账转移 + 明细行删除的原子性交给仓储的「一个事务」（落库真身同在
     * {@code AwardInventoryFlushService}）。守卫不过（0 行）按「已回补过」回 false，
     * 不抛——对账方据此留 ERROR 后继续收敛次数退还，不再重试这条。
     */
    @Override
    public boolean restitutePrereserve(String awardId, String bizType, String bizId, long quantity) {
        boolean restituted = awardRepository.restitutePrereserve(awardId, bizType, bizId, quantity);
        if (restituted) {
            log.info("孤儿预扣已回补（台账转回 + 明细行删除，同事务） awardId={} bizType={} bizId={} quantity={}",
                    awardId, bizType, bizId, quantity);
        } else {
            log.error("孤儿预扣回补守卫不过（可能已回补过或不变量破坏），留人工核对"
                    + " awardId={} bizType={} bizId={} quantity={}", awardId, bizType, bizId, quantity);
        }
        return restituted;
    }
}
