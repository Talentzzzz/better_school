package com.betterschool.marketing.domain.inventory.adapter.repository;

import com.betterschool.marketing.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.domain.inventory.model.valobj.OrphanPrereserveCandidateVO;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存域唯一仓储（一域一仓储），award-inventory 两层模型的两个层面共用这一个端口：
 * <ul>
 *   <li><b>配额池层</b>（外界投放，{@link InventorySegmentVO} 选边）：只有台账查询与分配转出——
 *       池不再被抽奖直接扣减，预扣类 SQL 已从池 DAO 退役；</li>
 *   <li><b>奖品库存层</b>（{@link AwardInventoryType} 是统一台账里的一列）：抽奖的预扣 / 确认 /
 *       幂等预检全部锚在 {@code awardId}（{@code draw_strategy_award_inventory} 按 award_id 1:1），
 *       实物/虚拟不再分 DAO，按 {@code type} 分支两条 UPDATE。</li>
 * </ul>
 * <p>
 * 事务：预扣的一组落库（{@link #flushDeductGroup}）、确认的一组落库（{@link #flushClaimGroup}）、
 * 分配（{@link #allocate}）都是「一次操作的原子边界」，由实现类用 {@code TransactionTemplate}
 * （REQUIRED）包住；其余方法是单条 SQL，不额外开事务。域服务只调一次仓储方法，不注入模板。
 * <p>
 * 批量落库的真身是独立的 {@code InventoryQuotaFlushService}，实现类把
 * {@link #flushDeductGroup} / {@link #flushClaimGroup} 委托给它——合并器内 flush
 * 与降级路径复用同一套 SQL（spec §2.6）。
 * <p>
 * 进程内合并器（{@code InventoryMerger}）也在 domain 层，直接依赖本端口回调落库，
 * 依赖单向：域服务 → 合并器 → 本端口 ← 实现类。**入队不在本端口**——域服务直接注入合并器
 * （{@code submitAndWait}），本端口只呈现存储操作，仓储因此不认识合并器，无循环依赖。
 */
public interface IInventoryRepository {

    // ---- 配额池层（外界投放：查询 + 分配转出，不被抽奖扣减） ----

    /** 池台账行（不限 state，open 与否由域服务判）；该段不存在这个 inv 时返回 null */
    InventoryLedgerVO queryLedger(InventorySegmentVO segment, String invId);

    /** 该段全部 open 的池台账 */
    List<InventoryLedgerVO> listOpenLedgers(InventorySegmentVO segment);

    /**
     * 分配（分配即转移，一个事务，spec §0 决策 1）：池 {@code surplus} 守卫转出 +
     * 奖品库存账户 {@code surplus} 转入 + 奖品侧 {@code AWARD_ALLOCATE} 明细行 +
     * 池侧分配流水行（独立流水表，双侧都有流水可对账）。
     * <p>
     * 幂等靠两边流水唯一键：奖品侧 {@code uk_award_biz (award_id, 'AWARD_ALLOCATE', 分配单号)} 与
     * 池侧 {@code uk_alloc_biz (分配单号)}——撞分配单号 = 本单已执行，按成功回放（不动池、不动账户）。
     * <p>
     * 池 {@code surplus} 不足（业务事实）返回 false，不抛异常；账户缺失 = 不变量破坏（抛）。
     * 低频运营操作：不进合并器，同步一事务。
     *
     * @param poolSegment 池段（QUOTA = 实物营销配额，VIRTUAL = 虚拟库存配额）
     * @param poolInvId   池台账业务 ID（转出来源）
     * @param awardId     奖品位 id（奖品库存账户维度；账户不存在则按池段建户）
     * @param quantity    本次分配份数
     * @param allocBizId  分配单号（两侧流水的幂等锚点）
     * @return true = 已转移（或本单已执行过，按成功回放）；false = 池余量不足
     */
    boolean allocate(InventorySegmentVO poolSegment, String poolInvId, String awardId,
                     long quantity, String allocBizId);

    // ---- 奖品库存层（抽奖直接扣减的账户） ----

    /** 奖品库存台账行（不限 state，open 与否由域服务判）；该 awardId 无账户时返回 null */
    InventoryLedgerVO queryAwardInventory(String awardId);

    /** 该账户全部 open 的奖品库存台账 */
    List<InventoryLedgerVO> listOpenAwardInventories();

    // ---- 预扣落库（一组一个事务） ----

    /**
     * 落库一组预扣（spec §4.1–§4.3）：**一组一个事务** = 组内明细 {@code INSERT IGNORE} 若干行 +
     * 台账一条 {@code UPDATE}；组 = 同一奖品库存账户上的一批请求。成功 = 已提交——返回时
     * {@code surplus} 已减、实物账户 {@code waiting} 已增 / 虚拟账户 {@code issued} 已增，明细恰好落上。
     * <p>
     * 幂等裁决完全交给明细唯一键 {@code uk_award_biz (award_id, biz_type, biz_id)}：撞键的请求不再产生扣减
     * （整批撞 = 扣 0、直接提交、按成功回放；部分撞 = 回查命中行、从本次扣减量里剔除）。
     * <p>
     * 扣不动不二分（spec §4.2）：{@code affected = 0} 时查剩余、截出 {@code k} 笔，走**第二事务路径**
     * 只插 {@code k} 行、只扣 {@code k} 份——明细与台账仍然平（插进去的是 k 行不是 N 行）；
     * {@code k = 0} 则整批 {@code INSUFFICIENT}。
     * <p>
     * 也是**降级路径**（spec §2.6）：合并器缓冲满 / 停机中，调用方线程用它同步扣一批（组大小 1），
     * 与合并器内的 flush 复用同一个方法——两套 SQL 迟早漂移，而漂移只在压力最大时暴露。
     *
     * @param type    组统一的账户类型（PHYSICAL 进 waiting / VIRTUAL 直接进 issued，两条 UPDATE）
     * @param awardId 组统一的奖品库存账户（= 奖品位 id）
     * @param requests 该组的请求，顺序 = 应当被截取的顺序（前 {@code k} 笔成功）
     * @return 与 {@code requests} 一一对应的逐件结果（{@code DEDUCTED} / {@code INSUFFICIENT}）
     * @throws IllegalStateException 台账行缺失（不变量破坏，整组回滚后抛出）
     */
    List<DeductResult> flushDeductGroup(AwardInventoryType type, String awardId,
                                        List<InventoryDeductRequestVO> requests);

    /**
     * 预扣明细是否已存在（业务幂等预检）：只在台账非 open 时调用，用于区分
     * 「真余量不足」与「这场流转在本账户上已落过」（重放）。只查 {@code flow_type = 1} 的预扣行。
     */
    boolean existsDeductDetail(String awardId, String bizType, String bizId);

    // ---- 兑换确认（仅实物账户有第二段流转；同步合并，一组一个事务） ----

    /**
     * 落库一组兑换确认（spec §4.5）：**一组一个事务** = 组内 {@code flow_type = 2} 明细**逐行**
     * {@code INSERT IGNORE} + 台账一条 {@code UPDATE}（{@code waiting -= c, issued += c}，
     * 转移量 = 新增行的份数和）。成功 = 已提交——返回时转移已落账，没有第二步。
     * <p>
     * 幂等裁决完全交给明细唯一键：撞 {@code uk_award_biz} 的件不再产生转移（全组撞 = 转移 0、
     * 直接提交、按成功回放）。确认批天然小（一份实物出库），逐行插不批插——「哪几行是新增」
     * 没有歧义，转移量精确等于新增量。
     * <p>
     * {@code waiting} 不足 = <b>不变量破坏</b>，不是业务失败：整组回滚（连确认明细一起）+
     * {@code error} 日志 + 抛可重试 ⇒ MQ 重投 ⇒ 死信。不复活 {@code INVENTORY_WAITING_SHORTAGE(4312)}，
     * 不做部分确认。方法只打 {@code type = 'PHYSICAL'} 的行（虚拟账户没有 {@code waiting}）。
     * <p>
     * 也是**降级路径**（spec §2.6）：合并器缓冲满 / 停机中，调用方线程用它同步确认一件
     * （组大小 1），与合并器内的 flush 复用同一个方法——两套 SQL 迟早漂移，而漂移只在压力最大时暴露。
     *
     * @param items 该组的确认件，顺序 = 入队顺序；{@code awardId} 在每个件里（组内统一，
     *              分组键保证），台账 {@code UPDATE} 取自 {@code items.get(0).awardId()}
     * @return 与 {@code items} 一一对应的逐件结果（现只有 {@code CONFIRMED}：撞键回放也是成功）
     * @throws IllegalStateException {@code waiting} 不足（不变量破坏，整组回滚后抛出）
     */
    List<ClaimOutcome> flushClaimGroup(List<InventoryClaimRequestVO> items);

    // ---- 孤儿预扣对账（spec User Stories 14–16；低频对账动作，不进合并器） ----

    /**
     * 孤儿预扣候选扫描：预扣行（{@code flow_type = 1} + {@code LOTTERY_DRAW}）且
     * {@code create_time} 早于 {@code before}，其 {@code biz_id}（= 抽奖记录 id）在
     * {@code draw_record} 无对应行（NOT EXISTS 只读反查对账判据的另一张域表）。按 id 升序 FIFO。
     */
    List<OrphanPrereserveCandidateVO> listOrphanPrereserveDetails(LocalDateTime before, int limit);

    /**
     * 回补一笔孤儿预扣（一个事务：台账条件转移 + 预扣明细行删除，守恒判据 B3 要求两者同提交）。
     * 台账按账户 {@code type} 分支：PHYSICAL 走 {@code waiting → surplus}（守卫
     * {@code waiting_qty >= qty}，照 {@code transferWaitingToIssued} 的反向转移形状），
     * VIRTUAL 走 {@code issued → surplus}（守卫 {@code issued_qty >= qty}）；
     * 不限量户（surplus=-1）不动 surplus，exhausted 户随回补放回 open（closed 不自动重开）。
     * 返回 false = 守卫不过（已回补过或不变量破坏），调用方留 ERROR 后继续，不重试这条。
     */
    boolean restitutePrereserve(String awardId, String bizType, String bizId, long quantity);
}
