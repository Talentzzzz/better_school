package com.betterschool.marketing.infrastructure.inventory.adapter.repository;

import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.infrastructure.merge.MergeConfig;
import com.betterschool.marketing.infrastructure.merge.MergeEngine;
import com.betterschool.marketing.infrastructure.merge.MergeResult;
import com.betterschool.marketing.infrastructure.merge.MergeStrategy;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 库存域的合并写入组件：把「库存的两类写动作」接进通用合并引擎，并把引擎终态翻成域的出口。
 *
 * <p><b>两个用途、两个引擎</b>：预扣与确认打的是两条不同的落库函数（{@link InventoryQuotaFlushService}
 * 的两个组方法，预扣的 {@code surplus} 不足是业务失败、确认的 {@code waiting} 不足是不变量破坏，
 * 失败域不同，不合成一条 SQL），因此各配一个 {@link MergeEngine}，策略在构造时定格到各自的引擎上。
 * 两个引擎各有自己的一份缓冲与分片线程——不同用途的件不会同批，也不互相等待。
 *
 * <p>通用引擎只回「落没落 / 结没结果」，不认识「扣到了 / 余量不足 / 要重试」这些业务口径，
 * 这一层就是库存口径的唯一落点：{@code COMPLETED} 取结果；{@code TIMEOUT}（可能已落）与
 * {@code FAILED}（确定没落）都回 {@code RETRY_LATER}——不切兜底、不落库，凭 {@code bizId} 重放，
 * 唯一键兜住不双扣。{@code BUFFER_FULL} / {@code CLOSED} 在引擎内部就被降级成本线程同步落库，
 * 不会冒到这一层（下面的分支只是枚举穷尽，不是真会走的路）。
 *
 * <p>分片键都是 {@code awardId}：同一奖品库存账户的件落同一分片、同一事务，预扣与确认也因此天然同片。
 *
 * <p>引擎带常驻线程，所以由本类持有并在 {@link #close()} 里收口——本类依赖落库服务，
 * Spring 销毁它必然早于连接池，排空时连接仍然可用。
 */
@Slf4j
@Component
public class InventoryQuotaMergeWriter {

    private final MergeEngine<InventoryDeductRequestVO, DeductResult> deductEngine;
    private final MergeEngine<InventoryClaimRequestVO, ClaimOutcome> claimEngine;

    public InventoryQuotaMergeWriter(MergeConfig mergeConfig, InventoryQuotaFlushService flushService) {
        this.deductEngine = new MergeEngine<>(mergeConfig, new MergeStrategy<>(
                "inventory-deduct",
                InventoryDeductRequestVO::awardId,
                // 组内 awardId 统一（分组键保证），type 也随账户唯一，取首件即可
                group -> flushService.flushDeductGroup(group.get(0).type(), group.get(0).awardId(), group)));
        this.claimEngine = new MergeEngine<>(mergeConfig, new MergeStrategy<>(
                "inventory-claim",
                InventoryClaimRequestVO::awardId,
                flushService::flushClaimGroup));
    }

    /**
     * 预扣一件。
     *
     * @return {@code DEDUCTED} = 已提交；{@code INSUFFICIENT} = 余量不足（业务事实）；
     *         {@code RETRY_LATER} = 结果未知或确定未落（抛可重试，凭 {@code bizId} 重放不双扣）
     */
    public DeductResult deduct(InventoryDeductRequestVO request) {
        MergeResult<DeductResult> outcome = deductEngine.commit(request);
        return switch (outcome.state()) {
            case COMPLETED -> outcome.result();
            case TIMEOUT -> {
                log.warn("预扣等窗口超时（结果未知，件大概率仍会落库） type={} awardId={} bizId={}",
                        request.type().getCode(), request.awardId(), request.bizId());
                yield DeductResult.RETRY_LATER;
            }
            case FAILED -> {
                log.warn("预扣落库失败（整组已回滚，一件未动） type={} awardId={} bizId={}",
                        request.type().getCode(), request.awardId(), request.bizId());
                yield DeductResult.RETRY_LATER;
            }
            // 这两态不会从 commit 返回（引擎内部已就地降级）；枚举穷尽所需
            case BUFFER_FULL, CLOSED -> throw new IllegalStateException(
                    "合并在入队阶段就该被降级，这两态不该冒到业务出口：" + outcome.state());
        };
    }

    /**
     * 兑换确认一件。
     *
     * @return {@code CONFIRMED} = 转移已提交（含撞键回放）；{@code RETRY_LATER} = 可能已确认或确定没确认，
     *         靠 MQ 重投撞唯一键回放或补上
     */
    public ClaimOutcome claim(InventoryClaimRequestVO request) {
        MergeResult<ClaimOutcome> outcome = claimEngine.commit(request);
        return switch (outcome.state()) {
            case COMPLETED -> outcome.result();
            case TIMEOUT -> {
                log.warn("兑换确认等窗口超时（可能已确认） awardId={} bizId={}", request.awardId(), request.bizId());
                yield ClaimOutcome.RETRY_LATER;
            }
            case FAILED -> {
                log.warn("兑换确认落库失败（整组已回滚） awardId={} bizId={}", request.awardId(), request.bizId());
                yield ClaimOutcome.RETRY_LATER;
            }
            // 同上：commit 不会返回这两态，留下只为枚举穷尽
            case BUFFER_FULL, CLOSED -> throw new IllegalStateException(
                    "合并在入队阶段就该被降级，这两态不该冒到业务出口：" + outcome.state());
        };
    }

    /** 两个引擎都排空才算排空（排障与集成测试读数用，业务路径不调） */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        if (!deductEngine.awaitIdle(timeoutMs)) {
            return false;
        }
        return claimEngine.awaitIdle(Math.max(1, deadline - System.currentTimeMillis()));
    }

    /** 两个引擎的积压之和（同上，只给排障与测试） */
    public int pending() {
        return deductEngine.pending() + claimEngine.pending();
    }

    /** 收口：两个引擎依次停收、排空、回填。顺序无关，各管自己那批件 */
    @PreDestroy
    public void close() {
        deductEngine.close();
        claimEngine.close();
    }
}
