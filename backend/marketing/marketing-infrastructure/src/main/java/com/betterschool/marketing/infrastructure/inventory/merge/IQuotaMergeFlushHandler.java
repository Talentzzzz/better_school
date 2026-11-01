package com.betterschool.marketing.infrastructure.inventory.merge;

import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;

import java.util.List;

/**
 * 落库方法：合并器唯一认识的出口（06 接预扣、07 接确认；本票只有测试替身）。
 *
 * <p><b>一组一个事务，组内只有一条台账 {@code UPDATE}</b>（spec §4.1）：组 = 同一分片内
 * 「动作 × segment × {@code invId}」。一组一事务比一批一事务多一层循环，换来三件事：
 * 锁集最小、失败只回滚一组、结果能精确回填到件；一批一事务会让两个分片以不同顺序锁同两行，
 * 多出一个死锁面。
 *
 * <p><b>两个方法是两个失败域，不合成一个</b>（spec §4.5）——所以是两条接口方法，不是
 * {@code flushGroup(action, …)} 加 {@code switch}：
 * <ul>
 *   <li>{@link #flushDeductGroup}：{@code surplus} 不足是<b>业务</b>（回 {@code INSUFFICIENT}，
 *       调用方切兜底档）；「探一次再切」的第二事务路径也在这里，且必须让明细与台账仍然平
 *       （插进去的是 {@code k} 行，不是 {@code N} 行）；</li>
 *   <li>{@link #flushClaimGroup}：{@code waiting} 不足是<b>不变量破坏</b>（整组回滚 + 抛，
 *       不做部分确认）——抛出去由合并器补 {@code FAILED}，调用方据此抛可重试，MQ 重投到死信。</li>
 * </ul>
 *
 * <h2>三条约定（与计数侧同一个口径）</h2>
 * <ol>
 *   <li><b>返回逐件结果，顺序与入参一致</b>；返回 {@code null}、长度不足或某一位为 {@code null}
 *       都算那几件漏回填，合并器补失败态（I1：绝不让调用方悬到超时）；</li>
 *   <li><b>抛异常 = 整组回滚</b>，组内所有件补失败态，别的组照常跑（一组一事务 ⇒ 失败不扩散）；</li>
 *   <li>落库方法<b>不得</b>在这里做降级 / 重试 / 拆分：降级是调用方线程的事（缓冲满 / 停机时
 *       用它自己调同一个方法、组大小 1），引擎只数件。</li>
 * </ol>
 *
 * <p>实现必须自己开事务（{@code @Transactional}），合并器不持有事务边界——它只保证
 * 「同一分片同一时刻至多一组在跑」。
 */
public interface IQuotaMergeFlushHandler {

    /**
     * 预扣的一组：{@code INSERT IGNORE} 明细 {@code N} 行 + 台账一条 {@code UPDATE}。
     *
     * @param segment  该组统一的段（分组键的一维：两段是两张表）
     * @param invId    该组统一的台账行主键（= 调用方给的 {@code shardKey}）
     * @param requests 该组的请求，顺序 = 入队顺序
     * @return 与 {@code requests} 一一对应的逐件结果（{@code DEDUCTED} / {@code INSUFFICIENT}）
     */
    List<DeductResult> flushDeductGroup(InventorySegmentVO segment,
                                       long invId,
                                       List<InventoryDeductRequestVO> requests);

    /**
     * 兑换确认的一组：{@code type = 2} 明细逐行 {@code INSERT IGNORE} + {@code waiting → issued} 一条
     * {@code UPDATE}。
     *
     * <p>不带 {@code segment}：确认只存在于实物段（虚拟段没有 {@code waiting}）。
     *
     * @param invId 该组统一的台账行主键（= 调用方给的 {@code shardKey}）
     * @param items 该组的确认件，顺序 = 入队顺序
     * @return 与 {@code items} 一一对应的逐件结果（现只有 {@code CONFIRMED}：撞键回放也是成功）
     */
    List<ClaimOutcome> flushClaimGroup(long invId, List<ClaimItem> items);
}
