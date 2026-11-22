package com.betterschool.marketing.domain.inventory.service;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.OrphanPrereserveCandidateVO;
import com.betterschool.marketing.types.inventory.AwardInventoryType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存配额服务（award-inventory 两层模型）：实物/虚拟**一条路径**，账户类型由
 * {@link AwardInventoryType} 表达（统一台账 {@code draw_strategy_award_inventory} 里的一列），
 * 预扣的账户维度是 {@code awardId}（奖品位 id，1 奖品位 1 库存账户）。
 * <p>
 * 抽奖不再直接扣配额池：池（外界投放）只被「分配」转移到奖品库存账户（见
 * {@code IInventoryAllocationService}），预扣/确认只打统一台账。
 * <p>
 * 预扣：进进程内合并器（{@code InventoryMerger}，同在 domain 层，域服务直接注入入队），
 * 请求等 {@code maxFlushDelayMillis} 后一组一事务落库——明细 {@code INSERT IGNORE} + 台账一条 {@code UPDATE}，
 * **成功 = 已提交**（返回时 {@code surplus} 已减、实物账户 {@code waiting} 已增 /
 * 虚拟账户 {@code issued} 已增）。没有第二步：不落 outbox、不等合并、没有 Redis 桶
 * （桶层已整层删除，台账三列是唯一真相）。
 * <p>
 * 三出口照旧（spec §4.4）：成功 / {@code INSUFFICIENT}（确定没扣，改发兜底档）/
 * {@code TIMEOUT_UNKNOWN}（还在缓冲或落库中，大概率会扣 ⇒ <b>不切兜底、不落库、抛可重试</b>）。
 * 缓冲满 / 停机中由服务层同步降级：调用方线程用**同一个落库方法**扣一批（组大小 1）。
 * <p>
 * 业务身份：明细带 {@code bizType} 与 {@code bizId = recordId}，唯一键
 * {@code uk_award_biz (award_id, biz_type, biz_id)} 保证同一笔业务在同一账户上只落一行；
 * 主档与兜底是两个账户（两个 awardId），互不撞键。撞键 = 这笔流转已落过 = 按成功回放
 * （在落库方法内由 {@code INSERT IGNORE} 裁决，见 {@code IInventoryRepository#flushDeductGroup}）。
 * 台账事务失败 ⇒ 整体回滚 ⇒ 抛可重试，凭 {@code bizId} 重试不双扣。
 */
public interface IInventoryQuotaService {

    /**
     * 预扣 quantity 份，并把业务身份 {@code (bizType, bizId)} 写进明细。
     *
     * @param type    目标账户类型：PHYSICAL = 实物奖品（两段式），VIRTUAL = 虚拟奖品（直接发放）
     * @param awardId 奖品位 id（= 奖品库存账户维度）
     * @param bizType {@code InventoryBizTypes} 的取值（抽奖域不认识库存列，由库存侧端口实现填）
     * @param bizId   业务 ID（抽奖 = {@code draw_record.record_id}）：明细唯一键的去重锚点
     * @return true = 已提交（台账已动）或这笔预扣已落过（撞业务唯一键 = 成功回放）；
     *         false = 余量不足（业务事实，调用方改发兜底档）
     * @throws com.betterschool.marketing.types.common.AppException 奖品库存账户不存在（配置错：
     *              奖品位没有可扣的库存），或预扣还在缓冲 / 落库中、或落库事务失败（可重试：
     *              不切兜底、不落库，凭同一业务身份重放）
     */
    boolean preDeduct(AwardInventoryType type, String awardId, long quantity, String bizType, String bizId);

    /**
     * 带兜底的预扣（spec §7.2）：主档扣不出 → 换兜底奖品位（可换类型）再扣；两边都扣不出 = FAILED。
     * {@code TIMEOUT_UNKNOWN} / 落库失败**不进入兜底分支**——直接抛可重试
     * （切兜底会扣两份库存发两个奖，spec §4.4）。主档与兜底在同一张明细表上是两个
     * {@code awardId} 前缀，唯一键互不撞——重放判定按兜底账户的 {@code (awardId, bizType, bizId)} 查。
     *
     * @param primary  主档请求（抽奖 = 实物奖品库存账户）
     * @param fallback 兜底请求（抽奖 = 兜底奖品位的库存账户）；null = 没有可扣的兜底库存
     */
    DeductOutcomeVO preDeductWithFallback(InventoryDeductRequestVO primary, InventoryDeductRequestVO fallback);

    /**
     * 兑换确认（仅实物账户，同步合并）：进与预扣同一个 {@code InventoryMerger}（同分片）的
     * 确认重载，一组一个事务——{@code flow_type = 2} 明细逐行 {@code INSERT IGNORE} + 台账一条
     * {@code UPDATE}（{@code waiting -= c, issued += c}，转移量 = 新增行数）。
     * **成功 = 转移已提交**：返回时落账已生效，没有第二步、没有合并消息。
     * <p>
     * {@code awardId} 空（不限量档，{@code draw_record.inv_id} 落 {@code NULL}）短路为
     * 「无事可做」，不与「账不平」共用异常。
     * <p>
     * 幂等：同一 {@code (flow_type = 2, bizType, bizId)} 重复确认撞唯一键 = 已确认过 = 按成功回放，
     * 不重复转移（裁决在落库方法内由 {@code INSERT IGNORE} 完成）。
     * <p>
     * {@code waiting} 不足 = 不变量破坏（不是业务失败）：整组回滚（连确认明细一起）+ error 日志
     * + 抛可重试 ⇒ MQ 重投 ⇒ 死信。不复活 {@code INVENTORY_WAITING_SHORTAGE(4312)}，不做部分确认。
     * 缓冲满 / 停机中：调用方线程同步确认一件（组大小 1，复用同一个落库方法）。
     *
     * @param awardId 奖品位 id（= 奖品库存账户维度；抽奖 = {@code draw_record.inv_id} 快照）
     * @param bizType {@code InventoryBizTypes.LOTTERY_CLAIM}
     * @param bizId   抽奖记录 id（= {@code draw_record.record_id}），不是独立兑换单号
     * @throws com.betterschool.marketing.types.common.AppException 还在缓冲 / 落库中、或落库失败
     *              （可重试：MQ 重投撞唯一键回放或补上）
     */
    void confirmIssued(String awardId, String bizType, String bizId, long quantity);

    // ---- 孤儿预扣对账（spec User Stories 14–16；低频对账动作，不进合并器） ----

    /**
     * 孤儿预扣候选扫描：预扣行（{@code flow_type = 1} + {@code LOTTERY_DRAW}）且
     * {@code create_time} 早于 {@code before}（补偿阈值，调用方算好传入），其 {@code biz_id}
     * （= 抽奖记录 id）在 {@code draw_record} 无对应行 = 主链路落库从未执行。
     * 按 id 升序 FIFO，上限 {@code limit}。
     *
     * @param before 补偿阈值时点（孤儿判据：预扣发生时刻早于它且记录仍不在）
     * @param limit  单轮扫描上限
     */
    List<OrphanPrereserveCandidateVO> listOrphanPrereserves(LocalDateTime before, int limit);

    /**
     * 回补一笔孤儿预扣（对账收敛的库存侧动作，低频直落不进合并器）：**一个事务**内完成
     * 台账条件转移 + 预扣明细行删除——台账按账户 {@code type} 分支：PHYSICAL 走
     * {@code waiting → surplus}（{@code waiting_qty >= qty} 守卫，照 {@code transferWaitingToIssued}
     * 的反向转移形状），VIRTUAL 走 {@code issued → surplus}（{@code issued_qty >= qty} 守卫）。
     * <p>
     * 删明细行与转移同事务是守恒判据 B3（{@code waiting + issued = Σ flow_type=1 quantity}）的要求：
     * 台账转回而不删行即破等式。幂等：行已删 / 量已回补的重复回补，守卫不过返回 false——
     * 对账方按「已回补过」继续（不抛），只留 ERROR 日志供人工核对不变量。
     *
     * @param awardId 奖品位 id（= 台账行，类型由台账行现读）
     * @param bizType 预扣明细的业务类型（抽奖 = {@code InventoryBizTypes.LOTTERY_DRAW}）
     * @param bizId   抽奖记录 id（= 预扣明细的业务键）
     * @return true = 台账已转回且明细行已删；false = 守卫不过（已回补过或不变量破坏，调用方留痕后继续）
     */
    boolean restitutePrereserve(String awardId, String bizType, String bizId, long quantity);
}
