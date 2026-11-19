package com.betterschool.marketing.domain.raffle.adapter.port.inventory;

import java.time.LocalDateTime;
import java.util.List;

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
     * 孤儿预扣候选（对账扫描出参）：一行「预扣在、抽奖记录不在」的事实。库存域的候选值对象
     * 不漏进抽奖域，端口翻成自己的形状（同 {@link Outcome} 的翻译口径）。
     */
    record OrphanPrereserveRef(String awardId, String recordId, long quantity) {
    }

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

    // ---- 孤儿预扣对账（spec User Stories 14–16；对账 job 经本端口收敛库存侧） ----

    /**
     * 孤儿预扣候选扫描：预扣在（{@code flow_type = 1} + {@code LOTTERY_DRAW}）、抽奖记录不在
     * （{@code biz_id} 在 {@code draw_record} 无对应行）、且预扣发生时刻早于 {@code before}
     * （补偿阈值）。按 id 升序 FIFO，上限 {@code limit}。
     */
    List<OrphanPrereserveRef> listOrphanPrereserves(LocalDateTime before, int limit);

    /**
     * 回补一笔孤儿预扣（库存侧动作由端口委托库存域）：一个事务内完成台账条件转移
     * （PHYSICAL {@code waiting → surplus} / VIRTUAL {@code issued → surplus}）+ 预扣明细行删除
     * （守恒判据 B3）。返回 false = 守卫不过（已回补过或不变量破坏），调用方留 ERROR 后继续。
     */
    boolean restituteOrphanPrereserve(String awardId, String recordId, long quantity);
}
