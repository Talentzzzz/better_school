package com.betterschool.infrastructure.adapter.repository.inventory;

import com.betterschool.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.domain.inventory.model.valobj.DeductResult;
import com.betterschool.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.infrastructure.merge.MergeConfig;
import com.betterschool.infrastructure.merge.MergeEngine;
import com.betterschool.infrastructure.merge.MergeResult;
import com.betterschool.infrastructure.merge.MergeStrategy;
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
 * 这一层就是库存口径的唯一落点：{@code COMPLETED} 取结果；{@code FAILED}（确定没落）回
 * {@code RETRY_LATER}——不切兜底、不落库，凭 {@code bizId} 重放，唯一键兜住不双扣。
 * 缓冲满 / 停机中不走「就地落库」的降级，引擎直接回 {@code FAILED}——件从未
 * 入队、必未落库，与「确定没落」同口径，同样凭 {@code bizId} 重放。
 *
 * <p>分片键都是 {@code awardId}：同一奖品库存账户的件落同一分片、同一事务，预扣与确认也因此天然同片。
 *
 * <p>引擎的发车跑在调用方线程上（无常驻线程），但仍在 {@link #close()} 里收口——本类依赖落库服务，
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
     *         {@code RETRY_LATER} = 确定未落（抛可重试，凭 {@code bizId} 重放不双扣）
     */
    public DeductResult deduct(InventoryDeductRequestVO request) {
        MergeResult<DeductResult> outcome = deductEngine.commit(request);
        return switch (outcome.state()) {
            case COMPLETED -> outcome.result();
            case FAILED -> {
                log.warn("预扣落库失败（整组已回滚，一件未动） type={} awardId={} bizId={}",
                        request.type().getCode(), request.awardId(), request.bizId());
                yield DeductResult.RETRY_LATER;
            }
        };
    }

    /**
     * 兑换确认一件。
     *
     * @return {@code CONFIRMED} = 转移已提交（含撞键回放）；{@code RETRY_LATER} = 确定没确认，
     *         靠 MQ 重投撞唯一键回放或补上
     */
    public ClaimOutcome claim(InventoryClaimRequestVO request) {
        MergeResult<ClaimOutcome> outcome = claimEngine.commit(request);
        return switch (outcome.state()) {
            case COMPLETED -> outcome.result();
            case FAILED -> {
                log.warn("兑换确认落库失败（整组已回滚） awardId={} bizId={}", request.awardId(), request.bizId());
                yield ClaimOutcome.RETRY_LATER;
            }
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
