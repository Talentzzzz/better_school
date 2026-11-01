package com.betterschool.marketing.domain.inventory.adapter.port;

import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;

/**
 * 库存进程内合并器的域端口（CLAUDE.md §1：domain 只注入本域仓储与本域 port，合并器实例在 infrastructure）。
 * <p>
 * 预扣与兑换确认走 {@code QuotaMergeBuffer.submitAndWait} 的两个重载（spec §2.2 / §4.5）：
 * 共用同一实例与分片（分片键 {@code invId}，由调用方显式给——合并器不解析请求体），
 * 内部落到两个各自一个事务的组方法——**不合成一个方法**，两个失败域的语义不同
 * （预扣的 {@code surplus} 不足是业务，确认的 {@code waiting} 不足是不变量破坏）。
 * <ul>
 *   <li>预扣（{@link #submitAndWait(long, InventoryDeductRequestVO)}）：请求等 {@code windowMs} 后
 *       一组一事务落库，成功 = 已提交（{@code COMMIT} 之后才说成功）。引擎只回答「成功 / 业务失败 /
 *       没接住」：</li>
 *   <li>{@link DeductResult#DEDUCTED}：台账已动，继续；</li>
 *   <li>{@link DeductResult#INSUFFICIENT}：确定没扣，改发兜底档；</li>
 *   <li>{@link DeductResult#TIMEOUT_UNKNOWN}：还在缓冲或落库中，大概率会扣 ⇒
 *       <b>不切兜底、不落库、抛可重试</b>；</li>
 *   <li>{@link DeductResult#BUFFER_FULL} / {@link DeductResult#CLOSED}：件未入队 ⇒
 *       调用方线程同步扣一批（组大小 1，复用同一个落库方法）；</li>
 *   <li>{@link DeductResult#FAILED}：事务已回滚或从未开始 ⇒ 抛可重试，凭 {@code bizId} 重试不双扣。</li>
 * </ul>
 * 确认（{@link #submitAndWait(long, ClaimItem)}）只有两条业务出口：{@link ClaimOutcome#CONFIRMED}
 * （含撞键回放）与可重试（{@code TIMEOUT_UNKNOWN} / {@code FAILED} 合一抛，幂等键兜住重投）；
 * {@code BUFFER_FULL} / {@code CLOSED} 同样降级为调用方线程同步确认一件。
 */
public interface IQuotaMergePort {

    /**
     * 预扣（等结果）。
     *
     * @param invId 台账行主键（= 分片键），与 {@code request.invId()} 必须一致
     */
    DeductResult submitAndWait(long invId, InventoryDeductRequestVO request);

    /**
     * 兑换确认（等结果，仅实物段）。
     *
     * @param invId 台账行主键（= 分片键）；{@code invId <= 0} 的短路在调用方做
     */
    ClaimOutcome submitAndWait(long invId, ClaimItem item);
}
