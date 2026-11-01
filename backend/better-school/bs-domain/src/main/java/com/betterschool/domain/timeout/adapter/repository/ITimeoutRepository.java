package com.betterschool.domain.timeout.adapter.repository;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 超时任务仓储（域层定义，infrastructure 实现；一域一仓储，读写同接口）。
 * <p>
 * 注册边（内嵌化票 03）：各域仓储在业务事务内调 {@link #register} 直插 {@code timeout_task}，
 * 超时行随业务事务提交/回滚（同生同死）；{@code insertIfAbsent} / {@code findByKey} /
 * {@code reviveTerminal} / {@code cancelActive} 是按键读写的底层原语，{@link #register} 的三分支
 * 幂等裁决立在它们之上。扫描捞批 {@link #selectDueBatch} 与结算三桶（票 04）在其上再叠一层；
 * 死信管理面（票 06）的列表/翻页方法见末段——槽位口径见 {@code TimeoutTaskShardSlots}
 * （单点函数，勿在别处重算）。
 * <p>
 * 事务口径（backend/CLAUDE.md §1）：每个方法各自是<b>单条语句</b>的原子操作，幂等的裁决权在库上
 * （uk_type_biz + 条件 UPDATE），不在「读-判-写」的时序里，因此自身不需要显式事务模板；
 * 由调用方（票 03 的业务仓储事务内直插）经 Spring 事务同步合流到同一条连接——
 * 传播语义天然 REQUIRED。域服务不注入 TransactionTemplate。
 * <p>
 * 结算那一步**故意不包事务**：一个批分三桶各是一条条件 UPDATE，跨语句的中间态是可接受的
 * （每行仍只被一条语句打中，且都带未终态守卫），语义与逐行结算时代一致——靠幂等 + 重试兜底，
 * 不靠事务把「一批要么全成要么全不成」当成需求（回调已经发生，回滚结算并不能撤销业务动作）。
 */
public interface ITimeoutRepository {

    /**
     * 注册一条超时任务（业务事务内直插，内嵌化票 03）：幂等裁决收成<b>三分支</b>——
     * <ol>
     *   <li>同键无行 → 新插入（WAITING、next_fire_time = action_time）；</li>
     *   <li>同键在途（WAITING/RETRYING）→ 幂等成功、<b>不改期</b>（沿用首次注册的时刻）；</li>
     *   <li>同键终态（SUCCESS/FAILED/CANCELED）→ 复活刷新 {@code action_time}（重开一轮）。</li>
     * </ol>
     * 原独立中心时代的「不同应用名冲突失败」随回调路由列概念消失；outbox 的「同键复位」
     * 语义由本三分支接管（同键复位不再需要「先 cancel 再复位」纪律——那是注册在途竞态的产物，
     * 同事务直插后结构性消失）。
     *
     * @return true = 本次注册生效（新插入或复活）；false = 在途同键幂等吸收（无副作用）
     */
    boolean register(TimeoutTaskEntity task);

    /**
     * 不存在才插入。
     *
     * @return true = 新插入成功；false = 撞上 uk_type_biz（同键已有行，由调用方查行走「已存在」分支）
     */
    boolean insertIfAbsent(TimeoutTaskEntity task);

    /** 按自然键取行；不存在返回 {@code null} */
    TimeoutTaskEntity findByKey(String taskType, String bizId);

    /**
     * 终态行复活为注册态：{@code status = WAITING}、{@code action_time = next_fire_time = 新时刻}、
     * {@code retry_count = 0}。条件 UPDATE 只打终态行（SUCCESS/FAILED/CANCELED），
     * 「在途行不改期」的幂等语义由库原子裁决。
     *
     * @return true = 命中并复活；false = 行不在终态（在途，幂等不改期）
     */
    boolean reviveTerminal(TimeoutTaskEntity task);

    /**
     * 未终态行软置 CANCELED（条件 UPDATE 只打 WAITING/RETRYING）。
     *
     * @return true = 确有行被取消；false = 行不存在或已是终态（无副作用）
     */
    boolean cancelActive(String taskType, String bizId);

    // ── 扫描与结算（票 04）──────────────────────────────────────────────────────────
    // 这几条是「按槽位」而不是「按 bizId」路由的：捞批的入参就是槽位号（分片 job 认领得到的），
    // 结算用的是批上带的槽位。槽位怎么来是调用方的事，仓储只负责把槽位塞进 hint。

    /**
     * 扫描捞批：某槽位上「未终态且已到点」的行，按 {@code next_fire_time} 升序取 {@code limit} 条。
     * <p>
     * 入参是槽位号而不是分片键——这条 SQL 里没有 {@code task_type}/{@code biz_id}，路由靠仓储层塞进
     * HintManager 的槽位。到期判据用调用方给的 {@code dueBefore}（不是库里的 {@code now()}）：
     * 一个 tick 内固定不动的时刻，重试行被退避推后可保证本轮不再被捞起（循环拉批到空的终止条件）。
     * <p>
     * 返回的行带上 {@code shardSlot}（= 本次入参）——结算批量化后仓储层要用**批的**槽位塞 hint。
     */
    List<TimeoutTaskEntity> selectDueBatch(int slot, LocalDateTime dueBefore, int limit);

    /**
     * 结算·成功桶：一批未终态行**一条语句**批量置 SUCCESS 终态。
     * <p>
     * 批量天然单表：一个批是 {@link #selectDueBatch 按槽位}捞出来的，本来就在同一张物理分表上；
     * {@code slot} 就是那个槽位，仓储层拿它塞 hint（整批一条 UPDATE，逐行算槽位没有意义）。
     * <p>
     * 只打未终态行——「回调在途被取消」等竞态仍由库原子裁决，因此**返回行数**可能与入参条数不等：
     * 差值 = 已被别的路径改走/已终态的行数，不是错误。
     */
    int markSuccessBatch(int slot, String taskType, List<String> bizIds);

    /**
     * 结算·重试桶：一批未终态行批量置 RETRYING、{@code retry_count + 1}，并**逐行**后移到各自的退避时刻
     * （退避公式在域服务里算好，仓储只管落库——SQL 里不用 {@code INTERVAL ?}，见票 02 的形态避让）。
     * <p>
     * 目标时刻按行不同（重试行的 {@code retry_count} 不同 ⇒ 退避不同），所以这条语句的
     * {@code SET next_fire_time} 走 CASE；{@code bizId → nextFireTime} 的映射由域服务算好，
     * **键序必须在语句里两次出现时保持一致**（错位会把退避写到别的行上）。
     *
     * @return 本桶实际改到的行数（差值 = 已被取消/已终态的行）
     */
    int markRetryBatch(int slot, String taskType, LinkedHashMap<String, LocalDateTime> retryTimes);

    /**
     * 结算·死信桶：一批未终态行批量置 FAILED 终态、{@code retry_count + 1}。
     * <p>
     * 不改 {@code next_fire_time}：FAILED 是终态，扫描 WHERE 永远排除它，这个值不再有读者。
     * 与 {@link #markRetryBatch} 一样是条件 UPDATE，所以「回调在途被取消/被重发」都不会被这一步翻掉。
     *
     * @return 本桶实际改到的行数（差值 = 已被取消/已终态的行）
     */
    int markDeadBatch(int slot, String taskType, List<String> bizIds);

    /**
     * 死信重发：FAILED 行置回 RETRYING、{@code retry_count} 清零、{@code next_fire_time = now}
     * ——「立即重开一轮」，**不动 {@code action_time}**（理论到期时刻不可变）。
     * <p>
     * 只打 FAILED 行：重发是死信专属动作，未终态行（在途）与其余终态行都不该被它碰到。
     *
     * @return true = 确有行被重开；false = 行不是 FAILED（或不存在），无副作用
     */
    boolean resendDeadLetter(String taskType, String bizId);

    /**
     * 死信取消：FAILED 行置 CANCELED 终态。
     * <p>
     * 与 {@link #cancelActive}（业务取消，只打未终态行）是两条路：这里专收死信，
     * 所以「取消一个正在重试的任务」仍走业务侧，语义不混。
     *
     * @return true = 确有行被置 CANCELED；false = 行不是 FAILED（或不存在），无副作用
     */
    boolean cancelDeadLetter(String taskType, String bizId);

    // ── 死信管理面·列表（票 06）────────────────────────────────────────────────────

    /**
     * 死信总数（管理面翻页的 total）。
     * <p>
     * <b>本组方法故意不塞 hint</b>：列表按状态翻、SQL 里没有分片键，要让 ShardingSphere 广播到
     * 16 张分表再归并——塞了 hint 会只查一张分表、翻不出全量死信（全仓唯一允许漏 hint 的两条语句，
     * 理由见实现类）。内嵌化后这仍是广播：分表没消失，只是同库了。
     */
    long countDeadLetters();

    /**
     * 死信列表的一页：{@code updated_at} 倒序（最近进死信的在前）、{@code id} 倒序作第二排序键
     * （压住同表内同秒撞车；跨表自增 id 理论可撞值，极端行与原独立中心同口径），
     * {@code LIMIT/OFFSET} 由 SS 在归并阶段做精确截断。
     *
     * @param offset 偏移行数（调用方按 {@code (page-1) * pageSize} 算好传入）
     * @param limit  本页行数（HTTP 边界已夹紧到 ≤ 50）
     */
    List<TimeoutTaskEntity> selectDeadLetters(int offset, int limit);
}
