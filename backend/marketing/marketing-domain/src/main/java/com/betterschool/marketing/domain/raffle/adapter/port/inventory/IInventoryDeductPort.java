package com.betterschool.marketing.domain.raffle.adapter.port.inventory;

/**
 * 抽奖域 → 库存域的发放预扣端口（CLAUDE.md §1：跨域写组合不在域服务间直接注入）。
 * 实现在 infrastructure，委托库存域的 {@code IInventoryQuotaService.preDeduct} /
 * {@code IInventoryQuotaService.preDeductWithFallback}（账户类型与 bizType 由端口实现填）；
 * 判据一致：余量不足 = 业务事实（返回 false / FALLBACK 语义），故障 = 异常抛出（调用方停止发放）。
 * <p>
 * award-inventory 两层模型：预扣的账户维度是 {@code awardId}（奖品位 id，奖品库存账户按 award_id
 * 1:1）——抽奖不再持有「池 inv_id」的概念，投放池只被「分配」转移到奖品位账户。
 * 两个方法都带 {@code recordId}（= {@code draw_record.record_id}）：库存域把它写成明细的业务 ID
 * （{@code biz_type} 由库存侧实现填，抽奖域不认识库存的表列），业务唯一键据此做预扣幂等；
 * 主档与兜底是两个账户（两个 awardId），明细唯一键互不撞。
 */
public interface IInventoryDeductPort {

    /** 预扣结果：DEDUCTED = 已扣；FALLBACK = 主档不足改扣兜底奖品位成功；FAILED = 连兜底也扣不出 */
    enum Outcome { DEDUCTED, FALLBACK, FAILED }

    /**
     * 虚拟奖品库存账户预扣 quantity 份（POINTS / DRAW_COUNT 奖品）。
     *
     * @param awardId  奖品位 id（= 奖品库存账户维度）
     * @param recordId 抽奖记录 id（= {@code draw_record.record_id}）：明细业务键，撞键按成功回放
     * @return true = 已扣（台账已动、明细落了）或这笔预扣已落过；false = 账户余量不足
     * @throws com.betterschool.marketing.types.common.AppException 奖品位没有库存账户（未投放的配置错）
     */
    boolean preDeductVirtual(String awardId, long quantity, String recordId);

    /**
     * 实物奖品库存账户带兜底的预扣：主档账户不足改扣兜底奖品位（虚拟奖品账户），主档零写入（不产生悬挂 waiting）。
     *
     * @param fallbackAwardId 兜底奖品位的 id（null = 没有可扣的兜底库存）
     * @param recordId        抽奖记录 id（= {@code draw_record.record_id}）：明细业务键，撞键按成功回放
     * @return 见 {@link Outcome}
     * @throws com.betterschool.marketing.types.common.AppException 奖品位没有库存账户（未投放的配置错）
     */
    Outcome preDeductPhysicalWithFallback(String awardId, String fallbackAwardId, long quantity, String recordId);
}
