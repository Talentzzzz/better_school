package com.betterschool.marketing.domain.inventory.service;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;

/**
 * 库存配额服务：两段（虚拟库存配额 / 实物营销配额）**一条路径**，段由 {@link InventorySegmentVO} 选。
 * <p>
 * 预扣（票 06）：进进程内合并器 {@code QuotaMergeBuffer}（经 {@code IQuotaMergePort}），
 * 请求等 {@code windowMs} 后一组一事务落库——明细 {@code INSERT IGNORE} + 台账一条 {@code UPDATE}，
 * **成功 = 已提交**（返回时 {@code surplus} 已减、实物段 {@code waiting} 已增 / 虚拟段 {@code issued} 已增）。
 * 没有第二步：不落 outbox、不等合并、没有 Redis 桶（桶层已整层删除，台账三列是唯一真相）。
 * <p>
 * 三出口照旧（spec §4.4）：成功 / {@code INSUFFICIENT}（确定没扣，改发兜底档）/
 * {@code TIMEOUT_UNKNOWN}（还在缓冲或落库中，大概率会扣 ⇒ <b>不切兜底、不落库、抛可重试</b>）。
 * 缓冲满 / 停机中由服务层同步降级：调用方线程用**同一个落库方法**扣一批（组大小 1）。
 * <p>
 * 业务身份：明细带 {@code bizType} 与 {@code bizId = recordId}，唯一键保证同一笔业务只落一行；
 * 撞键 = 这笔流转已落过 = 按成功回放（在落库方法内由 {@code INSERT IGNORE} 裁决，见
 * {@code IInventoryRepository#flushDeductGroup}）。台账事务失败 ⇒ 整体回滚 ⇒ 抛可重试，
 * 凭 {@code bizId} 重试不双扣。
 */
public interface IInventoryQuotaService {

    /**
     * 预扣 quantity 份，并把业务身份 {@code (bizType, bizId)} 写进明细。
     *
     * @param segment 目标段：QUOTA = 实物营销配额，VIRTUAL = 虚拟库存配额
     * @param bizType {@code InventoryBizTypes} 的取值（抽奖域不认识库存列，由库存侧端口实现填）
     * @param bizId   业务 ID（抽奖 = {@code draw_record.record_id}）：明细唯一键的去重锚点
     * @return true = 已提交（台账已动）或这笔预扣已落过（撞业务唯一键 = 成功回放）；
     *         false = 余量不足（业务事实，调用方改发兜底档）
     * @throws com.betterschool.marketing.types.common.AppException inv_id 查无此库存（参数错），
     *              或预扣还在缓冲 / 落库中、或落库事务失败（可重试：不切兜底、不落库，凭同一业务身份重放）
     */
    boolean preDeduct(InventorySegmentVO segment, String invId, long quantity, String bizType, String bizId);

    /**
     * 带兜底的预扣（spec §7.2）：主档扣不出 → 换个 inv_id（可换段）再扣；两段都扣不出 = FAILED。
     * {@code TIMEOUT_UNKNOWN} / 落库失败**不进入兜底分支**——直接抛可重试
     * （切兜底会扣两份库存发两个奖，spec §4.4）。
     *
     * @param primary  主档请求（抽奖 = 实物营销配额）
     * @param fallback 兜底请求（抽奖 = 兜底奖品的虚拟权益池）；null = 没有可扣的兜底库存
     */
    DeductOutcomeVO preDeductWithFallback(InventoryDeductRequestVO primary, InventoryDeductRequestVO fallback);

    /**
     * 兑换确认（仅实物段，票 07 起同步合并）：进与预扣同一个 {@code QuotaMergeBuffer}（同分片）的
     * 确认重载，一组一个事务——{@code type = 2} 明细逐行 {@code INSERT IGNORE} + 台账一条
     * {@code UPDATE}（{@code waiting -= c, issued += c}，转移量 = 新增行数）。
     * **成功 = 转移已提交**：返回时落账已生效，没有第二步、没有合并消息。
     * <p>
     * {@code invId <= 0}（不限量档，{@code draw_record.inv_id} 落 {@code NULL}）短路为
     * 「无事可做」，不与「账不平」共用异常。
     * <p>
     * 幂等：同一 {@code (type = 2, bizType, bizId)} 重复确认撞唯一键 = 已确认过 = 按成功回放，
     * 不重复转移（裁决在落库方法内由 {@code INSERT IGNORE} 完成）。
     * <p>
     * {@code waiting} 不足 = 不变量破坏（不是业务失败）：整组回滚（连确认明细一起）+ error 日志
     * + 抛可重试 ⇒ MQ 重投 ⇒ 死信。不复活 {@code INVENTORY_WAITING_SHORTAGE(4312)}，不做部分确认。
     * 缓冲满 / 停机中：调用方线程同步确认一件（组大小 1，复用同一个落库方法）。
     *
     * @param bizType {@code InventoryBizTypes.LOTTERY_CLAIM}
     * @param bizId   抽奖记录 id（= {@code draw_record.record_id}），不是独立兑换单号
     * @throws com.betterschool.marketing.types.common.AppException 还在缓冲 / 落库中、或落库失败
     *              （可重试：MQ 重投撞唯一键回放或补上）
     */
    void confirmIssued(String invId, String bizType, String bizId, long quantity);
}
