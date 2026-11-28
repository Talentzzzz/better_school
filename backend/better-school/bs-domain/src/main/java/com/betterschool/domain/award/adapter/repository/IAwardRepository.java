package com.betterschool.domain.award.adapter.repository;

import com.betterschool.domain.award.model.valobj.AwardClaimRequestVO;
import com.betterschool.domain.award.model.valobj.AwardDeductRequestVO;
import com.betterschool.domain.award.model.valobj.AwardLedgerVO;
import com.betterschool.domain.award.model.valobj.ClaimOutcomeEnum;
import com.betterschool.domain.award.model.valobj.DeductResultEnum;
import com.betterschool.domain.award.model.valobj.OrphanPrereserveCandidateVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 奖品域唯一仓储（一域一仓储）：奖品库存台账（{@code award_inventory}，按 {@code award_id} 1:1）
 * 的维护、预扣 / 确认 / 幂等预检全部走这一个端口，实物 / 虚拟不分 DAO，按账户 {@code type}
 * 分支两条 UPDATE。
 *
 * <h2>写入口是「一件」语义，批量与降级是实现细节</h2>
 * {@link #deduct} / {@link #claim} 各收<b>一件</b>、各回一个业务结论。
 * 「这一件是不是并进一批落库、走没走合并、合并器忙时怎么降级」全部在实现里
 * （{@code infrastructure/merge}：合并引擎 + 写入组件），<b>域看不到也不该看到</b>——
 * 合并是性能手段，不是业务规则；摆进域，域就会开始按「缓冲满 / 超时」分支，
 * 而那些态本来只该在基础设施内部被翻译掉。
 *
 * <p>事务：奖品库存维护（{@link #upsertInventory}）、回补（{@link #restitutePrereserve}）、
 * 以及合并落库的「一组一个事务」都在实现里用 {@code TransactionTemplate}（REQUIRED）收口；
 * 域服务只调一次仓储方法，不注入模板、不自持事务边界。
 */
public interface IAwardRepository {

    // ---- 台账维护（运营直接投放 / 改量） ----

    /**
     * 直接维护奖品位库存账户（无池、无分配链路）：账户不存在则建户，已存在则按入参覆盖
     * {@code type / surplus_qty / value_qty / state}。低频运营操作，不进合并器。
     *
     * @param awardId    奖品位 id（= 库存账户维度）
     * @param type       账户类型（PHYSICAL / VIRTUAL）
     * @param surplusQty 可扣余量（{@code -1 = 不限量}）
     * @param valueQty   单次发放面额（仅 VIRTUAL，实物落 null）
     * @param state      0 open / 1 exhausted / 2 closed
     * @return true = 已写入（新建或更新）；false = 入参非法
     */
    boolean upsertInventory(String awardId, String type, Long surplusQty, Long valueQty, Integer state);

    /** 奖品库存台账行（不限 state，open 与否则由域服务判）；该 awardId 无账户时返回 null */
    AwardLedgerVO queryAwardInventory(String awardId);

    /** 该账户全部 open 的奖品库存台账 */
    List<AwardLedgerVO> listOpenAwardInventories();

    // ---- 预扣 / 确认（各一件；批量与降级在实现里） ----

    /**
     * 预扣一件（spec §4.1–§4.3）：明细 {@code INSERT IGNORE} + 台账一条 {@code UPDATE}，
     * <b>返回即已提交</b>——返回时 {@code surplus} 已减、实物账户 {@code waiting} 已增 /
     * 虚拟账户 {@code issued} 已增，明细恰好落上一行。没有第二步。
     * <p>
     * 幂等裁决完全交给明细唯一键 {@code uk_award_biz (award_id, biz_type, biz_id)}：撞键 =
     * 这条流转在本账户上已落过（同 token 并发双击的第二笔），按成功回放，不再产生扣减。
     * <p>
     * 扣不动不二分（spec §4.2）：余量不够时按剩余量截取，截到 0 就是 {@code INSUFFICIENT}——
     * 明细与台账仍然平。
     * <p>
     * <b>实现里发生、但域看不到的事</b>：这一件先进进程内合并器与同一账户的其它件攒成一批落库
     * （一个事务），等不到就回 {@code RETRY_LATER}；合并器缓冲满 / 停机中则由调用方线程同步落
     * 组大小 1（复用同一个落库方法）。三件事都不改变本方法的语义。
     *
     * @param request 预扣请求（账户类型 + awardId + 数量 + 业务身份）
     * @return {@code DEDUCTED} = 已提交；{@code INSUFFICIENT} = 余量不足（业务事实，可切兜底）；
     *         {@code RETRY_LATER} = 结果未知或确定未落（抛可重试，凭 {@code bizId} 重放不双扣）
     */
    DeductResultEnum deduct(AwardDeductRequestVO awardDeductRequestVO);

    /**
     * 预扣明细是否已存在（业务幂等预检）：只在台账非 open 时调用，用于区分
     * 「真余量不足」与「这场流转在本账户上已落过」（重放）。只查 {@code flow_type = 1} 的预扣行。
     */
    boolean existsDeductDetail(String awardId, String bizType, String bizId);

    /**
     * 兑换确认一件（spec §4.5）：{@code flow_type = 2} 明细 {@code INSERT IGNORE} +
     * 台账一条 {@code UPDATE}（{@code waiting -= c, issued += c}，转移量 = 新增行数），一个事务。
     * <b>返回即转移已提交</b>，没有第二步。
     * <p>
     * 幂等同上：撞 {@code uk_award_biz} = 已确认过 = 按成功回放，不重复转移。
     * <p>
     * {@code waiting} 不足 = <b>不变量破坏</b>，不是业务失败：整组回滚（连确认明细一起）+
     * {@code error} 日志 + 可重试 ⇒ MQ 重投 ⇒ 死信。不做部分确认。落库只打
     * {@code type = 'PHYSICAL'} 的行（虚拟账户没有 {@code waiting}）。
     *
     * @param request 确认请求（awardId + 业务身份 + 份数）
     * @return {@code CONFIRMED} = 已提交（含撞键回放）；{@code RETRY_LATER} = 可能已确认或确定没确认，
     *         靠幂等键重投放行
     */
    ClaimOutcomeEnum claim(AwardClaimRequestVO awardClaimRequestVO);

    // ---- 孤儿预扣对账（spec User Stories 14–16；低频对账动作，不进合并） ----

    /**
     * 孤儿预扣候选扫描：预扣行（{@code flow_type = 1} + {@code LOTTERY_DRAW}）且
     * {@code create_time} 早于 {@code before}，其 {@code biz_id}（= 抽奖记录 id）在
     * {@code draw_record} 无对应行（NOT EXISTS 只读反查对账判据的另一张域表）。按 id 升序 FIFO。
     */
    List<OrphanPrereserveCandidateVO> listOrphanPrereserveDetails(LocalDateTime before, int limit);

    /**
     * 回补一笔孤儿预扣（一个事务：台账条件转移 + 预扣明细行删除，守恒判据 B3 要求两者同提交）。
     * 台账按账户 {@code type} 分支：PHYSICAL 走 {@code waiting → surplus}（守卫
     * {@code waiting_qty >= qty}），VIRTUAL 走 {@code issued → surplus}（守卫
     * {@code issued_qty >= qty}）；不限量户（surplus=-1）不动 surplus，exhausted 户随回补放回 open
     * （closed 不自动重开）。
     * 返回 false = 守卫不过（已回补过或不变量破坏），调用方留 ERROR 后继续，不重试这条。
     */
    boolean restitutePrereserve(String awardId, String bizType, String bizId, long quantity);
}
