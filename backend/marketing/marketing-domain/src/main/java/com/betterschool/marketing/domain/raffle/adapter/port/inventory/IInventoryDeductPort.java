package com.betterschool.marketing.domain.raffle.adapter.port.inventory;

/**
 * 抽奖域 → 库存域的发放预扣端口（CLAUDE.md §1：跨域写组合不在域服务间直接注入）。
 * 实现在 infrastructure，委托库存域的 {@code IInventoryQuotaService.preDeduct} /
 * {@code IInventoryQuotaService.preDeductWithFallback}（段与 bizType 由端口实现填）；
 * 判据一致：余量不足 = 业务事实（返回 false / FALLBACK 语义），Redis 故障 = 异常抛出（调用方停止发放）。
 * <p>
 * 两个方法都带 {@code recordId}（= {@code draw_record.record_id}）：库存域把它写成明细的业务 ID
 * （{@code biz_type} 由库存侧实现填，抽奖域不认识库存的表列），业务唯一键据此做预扣幂等。
 */
public interface IInventoryDeductPort {

    /** 预扣结果：DEDUCTED = 已扣；FALLBACK = 配额不足改扣兜底虚拟权益成功；FAILED = 连兜底也扣不出 */
    enum Outcome { DEDUCTED, FALLBACK, FAILED }

    /**
     * 虚拟权益池预扣 quantity 份（POINTS / DRAW_COUNT 库存）。
     *
     * @param recordId 抽奖记录 id（= {@code draw_record.record_id}）：明细业务键，撞键按成功回放
     * @return true = 已扣（Redis 桶减了、明细落了 state=0）或这笔预扣已落过；false = 库存不可用或所有桶余量不足
     * @throws com.betterschool.marketing.types.common.AppException inv_id 查无此库存
     */
    boolean preDeductVirtual(String invId, long quantity, String recordId);

    /**
     * 实物营销配额带兜底的预扣：配额桶不足改扣兜底虚拟权益，配额段零写入（不产生悬挂 waiting_qty）。
     *
     * @param fallbackInvId 兜底虚拟权益的 inv_id（null = 没有可扣的兜底库存）
     * @param recordId      抽奖记录 id（= {@code draw_record.record_id}）：明细业务键，撞键按成功回放
     * @return 见 {@link Outcome}
     * @throws com.betterschool.marketing.types.common.AppException inv_id 查无此配额
     */
    Outcome preDeductPhysicalWithFallback(String invId, String fallbackInvId, long quantity, String recordId);
}
