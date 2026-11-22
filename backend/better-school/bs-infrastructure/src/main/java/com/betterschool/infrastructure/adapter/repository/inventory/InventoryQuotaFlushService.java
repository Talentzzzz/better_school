package com.betterschool.infrastructure.adapter.repository.inventory;

import com.betterschool.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.domain.inventory.model.valobj.DeductResult;
import com.betterschool.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.infrastructure.dao.inventory.IAwardInventoryDao;
import com.betterschool.infrastructure.dao.inventory.IAwardInventoryDetailDao;
import com.betterschool.infrastructure.dao.inventory.po.AwardInventoryDetailPO;
import com.betterschool.infrastructure.dao.inventory.po.AwardInventoryPO;
import com.betterschool.types.inventory.AwardInventoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 奖品库存批量落库真身：预扣与确认「一组一个事务」的 SQL 都在这里（award-inventory 两层模型——
 * 目标表是统一台账 {@code draw_strategy_award_inventory} / 统一明细，实物/虚拟一张表，按
 * {@code type} 分支两条 UPDATE）。
 * 本类的两个组方法是库存域交给通用合并引擎的<b>落库函数</b>（由 {@code InventoryQuotaMergeWriter}
 * 在 {@code MergeStrategy} 里绑定）：攒批引擎一组一次调它们，降级路径（缓冲满 / 停机时同步落库组大小 1）
 * 调的还是它们——两套 SQL 迟早漂移，而漂移只在压力最大时暴露。
 * <p>
 * 之所以从 {@code InventoryRepository} 拆出：落库只依赖 DAO + TransactionTemplate，与仓储门面无关；
 * 拆成独立组件后「攒批引擎 / 落库 SQL / 仓储门面」各一个类，依赖单向、无一环。
 * <p>
 * 事务：两个 flush 组方法各「一组一个事务」，由本类用 {@code TransactionTemplate}（REQUIRED）包住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryQuotaFlushService {

    private final IAwardInventoryDao awardInventoryDao;
    private final IAwardInventoryDetailDao awardInventoryDetailDao;
    private final TransactionTemplate transactionTemplate;

    // ---------------------------------------------------------------- 预扣落库（一组一个事务）

    /**
     * 一组一个事务（spec §4.1–§4.3），形状与参考工程一致但多一条「探一次再切」：
     * <pre>
     * BEGIN
     *   INSERT IGNORE INTO draw_strategy_award_inventory_detail (...) VALUES (...), (...)  -- 流水先行，唯一键裁决幂等
     *   UPDATE draw_strategy_award_inventory SET surplus -= N, waiting/issued += N WHERE type 分支
     * COMMIT
     * </pre>
     * 两条 SQL 的顺序不能颠倒：「流水先行 + 同事务」保证不会出现「记了流水没扣库存」——
     * 扣不动就整体回滚，流水一并消失。
     * <p>
     * 幂等：撞键快照在批量 {@code INSERT} **之前**查（同事务里刚插的行不算「已落过」），
     * 命中行（它们的量在更早的事务里已经扣过）从本次扣减量里剔除；
     * 整批都撞 = 扣 0、直接提交、按成功回放。快照之后、插入之前若有并发事务落了同业务键
     * （同 {@code bizId} 的在途重放撞上缓冲/降级两条路），本事务对「谁插的」不可知 ⇒
     * 整体回滚、重来一次让快照看到最新事实（至多两轮，再不齐即抛——并发重放的极端情况）。
     * <p>
     * 扣不动不二分（spec §4.2）：{@code affected = 0} 时第一事务整体回滚，探一次剩余量、
     * 截出 {@code k} 笔，走**第二事务路径**只插 {@code k} 行、只扣 {@code k} 份——
     * 明细与台账仍然平（插进去的是 k 行不是 N 行）；{@code k = 0} 则整批 {@code INSUFFICIENT}。
     */
    public List<DeductResult> flushDeductGroup(AwardInventoryType type, String awardId,
                                               List<InventoryDeductRequestVO> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            DeductFlush first = transactionTemplate.execute(status -> {
                // 撞键快照必须先于批量 INSERT：同事务刚插的行算「本事务新扣」，不能进剔除集
                Set<Integer> duplicates = findDuplicates(awardId, requests);
                // 批内同业务键（同 token 双击落进同一组）：第二笔是第一笔的重放，同样剔除
                markIntraBatchDuplicates(requests, duplicates);
                int expectedNew = requests.size() - duplicates.size();
                int inserted = insertDeductDetails(awardId, requests);
                if (inserted < expectedNew) {
                    // 快照与插入之间有并发事务落了同业务键：整体回滚，下一轮快照会看到它
                    status.setRollbackOnly();
                    return DeductFlush.stale();
                }
                long deductQty = totalQuantity(requests) - duplicateQuantity(requests, duplicates);
                if (deductQty <= 0) {
                    // 整批都是重放：扣 0、直接提交、按成功回放（新增流水 0 行，等于什么都没发生）
                    return DeductFlush.allDeducted(requests.size(), duplicates);
                }
                if (deductLedger(type, awardId, deductQty) == 0) {
                    // 扣不动：整组回滚（先插的流水一并消失），交给「探一次再切」
                    status.setRollbackOnly();
                    return DeductFlush.retry(duplicates);
                }
                return DeductFlush.allDeducted(requests.size(), duplicates);
            });
            if (first.staleSnapshot()) {
                continue;
            }
            if (!first.needRetry()) {
                return first.results();
            }

            // ── 第二事务路径：探一次再切（spec §4.2） ──
            Set<Integer> duplicates = first.duplicates();
            Long surplus = awardInventoryDao.selectSurplus(awardId);
            if (surplus == null) {
                // 台账行缺失：插了流水扣不了账 = 不变量破坏，让整组以失败收场（合并器补 FAILED）
                throw new IllegalStateException("预扣落库台账行缺失（不变量破坏） type=" + type.getCode()
                        + " awardId=" + awardId);
            }
            Cut cut = cutByRemaining(requests, duplicates, surplus);
            if (cut.indexes().isEmpty()) {
                // k = 0：整批 INSUFFICIENT（重放件仍按成功回放）
                return perItemResults(requests.size(), duplicates, Set.of());
            }
            boolean committed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                List<InventoryDeductRequestVO> cutRequests = cut.requests(requests);
                // 第二事务同样先取撞键快照：截出来的件理应全是新件，命中即被并发重放抢先 ⇒ 整组回滚
                boolean raced = !findDuplicates(awardId, cutRequests).isEmpty();
                int inserted = raced ? 0 : insertDeductDetails(awardId, cutRequests);
                if (raced || inserted < cutRequests.size()) {
                    // 探完到落库之间被并发消耗 / 抢落：整组回滚，宁少扣不多扣
                    status.setRollbackOnly();
                    return false;
                }
                if (deductLedger(type, awardId, totalQuantity(cutRequests)) == 0) {
                    // 探完还是扣不动（并发消耗）：整组回滚，全部 INSUFFICIENT
                    status.setRollbackOnly();
                    return false;
                }
                return true;
            }));
            // 注意 duplicates 的下标是相对 requests 的，cut 成功集合也要换算回原下标
            Set<Integer> deducted = committed ? cut.originalIndexes() : Set.of();
            return perItemResults(requests.size(), duplicates, deducted);
        }
        throw new IllegalStateException("预扣落库撞键快照连续过期（同 bizId 并发重放的极端情况，整组放弃）"
                + " type=" + type.getCode() + " awardId=" + awardId + " 件数=" + requests.size());
    }

    /** 批量 {@code INSERT IGNORE}：flow_type 恒 1（预扣），create_time/update_time 由 DB NOW() 写 */
    private int insertDeductDetails(String awardId, List<InventoryDeductRequestVO> requests) {
        return awardInventoryDetailDao.batchInsertIgnoreDeductDetails(awardId,
                requests.stream().map(r -> toDeductDetailPO(awardId, r)).toList());
    }

    /** 回查批内已存在的业务行：命中下标集合 + 命中量（它们的量在更早的事务里已经扣过） */
    private Set<Integer> findDuplicates(String awardId, List<InventoryDeductRequestVO> requests) {
        // 按 bizType 分组回查（唯一键的前缀），同组 bizType 恒一致，这里只为防混批
        Map<String, List<String>> byBizType = new LinkedHashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            InventoryDeductRequestVO request = requests.get(i);
            byBizType.computeIfAbsent(request.bizType(), key -> new ArrayList<>()).add(request.bizId());
        }
        Set<String> existingBizIds = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : byBizType.entrySet()) {
            List<String> bizIds = entry.getValue();
            // uk_award_biz (award_id, biz_type, biz_id)：按 award_id 前缀查——
            // 落在别的账户（主档 vs 兜底）上的同业务行不算「已落过」
            awardInventoryDetailDao.listExistingDeductDetails(awardId, entry.getKey(), bizIds)
                    .forEach(po -> existingBizIds.add(po.getBizId()));
        }
        Set<Integer> duplicates = new HashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            if (existingBizIds.contains(requests.get(i).bizId())) {
                duplicates.add(i);
            }
        }
        return duplicates;
    }

    /** 批内同业务键：同 {@code (bizType, bizId)} 在一组里出现多次（同 token 双击落进同一批），
     *  只有第一笔是真扣，其余按成功回放剔除 */
    private void markIntraBatchDuplicates(List<InventoryDeductRequestVO> requests, Set<Integer> duplicates) {
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            InventoryDeductRequestVO request = requests.get(i);
            if (!seenKeys.add(request.bizType() + ":" + request.bizId())) {
                duplicates.add(i);
            }
        }
    }

    private long totalQuantity(List<InventoryDeductRequestVO> requests) {
        return requests.stream().mapToLong(InventoryDeductRequestVO::quantity).sum();
    }

    private long duplicateQuantity(List<InventoryDeductRequestVO> requests, Set<Integer> duplicates) {
        return duplicates.stream().mapToLong(i -> requests.get(i).quantity()).sum();
    }

    /** 统一台账扣减：按 type 分支两条 UPDATE（PHYSICAL 进 waiting / VIRTUAL 直接进 issued） */
    private int deductLedger(AwardInventoryType type, String awardId, long quantity) {
        return switch (type) {
            case PHYSICAL -> awardInventoryDao.deductPhysical(awardId, quantity);
            case VIRTUAL -> awardInventoryDao.deductVirtual(awardId, quantity);
        };
    }

    /**
     * 按「剩余量」截出前 {@code k} 笔（spec §4.2：{@code k = min(剩余, 批内笔数)}）；
     * 每件数量恒 1 时退化为字面截取。{@code surplus < 0} = 不限量，全部截取。
     */
    private Cut cutByRemaining(List<InventoryDeductRequestVO> requests, Set<Integer> duplicates, long surplus) {
        List<Integer> indexes = new ArrayList<>(requests.size());
        long taken = 0;
        for (int i = 0; i < requests.size(); i++) {
            if (duplicates.contains(i)) {
                continue;
            }
            long quantity = requests.get(i).quantity();
            if (surplus >= 0 && taken + quantity > surplus) {
                break;
            }
            taken += quantity;
            indexes.add(i);
        }
        return new Cut(indexes);
    }

    private List<DeductResult> perItemResults(int size, Set<Integer> duplicates, Set<Integer> deducted) {
        List<DeductResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            // 撞键 = 这笔已落过 = 成功回放；本次事务新扣到的 = 成功；其余 = 余量不足
            results.add(duplicates.contains(i) || deducted.contains(i)
                    ? DeductResult.DEDUCTED : DeductResult.INSUFFICIENT);
        }
        return results;
    }

    /** 一次组内落库的中间结果：{@code staleSnapshot} = 撞键快照过期（回滚重取）；{@code needRetry} = 台账没动过（待探一次再切） */
    private static final class DeductFlush {

        private final List<DeductResult> results;
        private final Set<Integer> duplicates;
        private final boolean needRetry;
        private final boolean staleSnapshot;

        private DeductFlush(List<DeductResult> results, Set<Integer> duplicates,
                            boolean needRetry, boolean staleSnapshot) {
            this.results = results;
            this.duplicates = duplicates;
            this.needRetry = needRetry;
            this.staleSnapshot = staleSnapshot;
        }

        public List<DeductResult> results() {
            return results;
        }

        public Set<Integer> duplicates() {
            return duplicates;
        }

        public boolean needRetry() {
            return needRetry;
        }

        public boolean staleSnapshot() {
            return staleSnapshot;
        }

        static DeductFlush allDeducted(int size, Set<Integer> duplicates) {
            List<DeductResult> results = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                results.add(DeductResult.DEDUCTED);
            }
            return new DeductFlush(results, duplicates, false, false);
        }

        static DeductFlush retry(Set<Integer> duplicates) {
            return new DeductFlush(null, duplicates, true, false);
        }

        static DeductFlush stale() {
            return new DeductFlush(null, Set.of(), false, true);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DeductFlush other)) return false;
            return needRetry == other.needRetry
                    && staleSnapshot == other.staleSnapshot
                    && Objects.equals(results, other.results)
                    && Objects.equals(duplicates, other.duplicates);
        }

        @Override
        public int hashCode() {
            return Objects.hash(results, duplicates, needRetry, staleSnapshot);
        }

        @Override
        public String toString() {
            return "DeductFlush[results=" + results + ", duplicates=" + duplicates
                    + ", needRetry=" + needRetry + ", staleSnapshot=" + staleSnapshot + "]";
        }
    }

    /** 「探一次再切」截出来的件：组内下标（回填用）+ 截取后的子序列（落库用） */
    private static final class Cut {

        private final List<Integer> indexes;

        private Cut(List<Integer> indexes) {
            this.indexes = indexes;
        }

        public List<Integer> indexes() {
            return indexes;
        }

        Set<Integer> originalIndexes() {
            return new HashSet<>(indexes);
        }

        List<InventoryDeductRequestVO> requests(List<InventoryDeductRequestVO> all) {
            return indexes.stream().map(all::get).toList();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Cut other)) return false;
            return Objects.equals(indexes, other.indexes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(indexes);
        }

        @Override
        public String toString() {
            return "Cut[indexes=" + indexes + "]";
        }
    }

    // ---------------------------------------------------------------- 合并器落库出口（确认）

    /**
     * 兑换确认的一组（spec §4.5），一个事务：
     * <pre>
     * BEGIN
     *   INSERT IGNORE INTO draw_strategy_award_inventory_detail (...)   -- flow_type = 2，逐行不批插
     *   UPDATE draw_strategy_award_inventory
     *     SET waiting_qty -= c, issued_qty += c                         -- c = 本次新增行数
     *    WHERE award_id = ? AND type = 'PHYSICAL' AND waiting_qty &gt;= c
     * COMMIT
     * </pre>
     * 顺序与预扣同一口径（流水先行）：转移守卫不过就整组回滚，确认明细一并消失——
     * 不会出现「记了确认没转账」。
     * <p>
     * 转移量 = 新增行的份数和（撞键回放的行不计，它们的量在更早的事务里已经转过了）；
     * 全组撞键 = 转移 0、直接提交、按成功回放。{@code waiting} 不足 = 不变量破坏（不是业务失败）：
     * error 日志 + 抛 {@link IllegalStateException} ⇒ 合并器整组补 {@code FAILED}
     * ⇒ 服务层抛可重试 ⇒ MQ 重投 ⇒ 死信（spec §4.5，不复活 4312、不做部分确认）。
     */
    public List<ClaimOutcome> flushClaimGroup(List<InventoryClaimRequestVO> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        // 组内 awardId 统一（引擎按「动作 × type × awardId」分组后才到这里），台账 UPDATE 取首件即可
        String awardId = items.get(0).awardId();
        transactionTemplate.executeWithoutResult(status -> {
            long transferQty = 0;
            for (InventoryClaimRequestVO item : items) {
                if (awardInventoryDetailDao.insertIgnoreClaimDetail(toClaimDetailPO(item)) > 0) {
                    // 只有新增行产生转移：撞键回放的行，它的量在更早的事务里已经转过了
                    transferQty += item.quantity();
                }
            }
            if (transferQty == 0) {
                // 整组撞 uk_award_biz = 都已确认过：不转移、直接提交、按成功回放
                return;
            }
            if (awardInventoryDao.transferWaitingToIssued(awardId, transferQty) == 0) {
                log.error("兑换确认守卫不过（waiting_qty < 转移量，不变量破坏，整组回滚）"
                        + " awardId={} 转移量={} 件数={}", awardId, transferQty, items.size());
                throw new IllegalStateException("兑换确认 waiting 不足（不变量破坏，整组回滚） awardId="
                        + awardId + " 转移量=" + transferQty + " 件数=" + items.size());
            }
        });
        List<ClaimOutcome> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            results.add(ClaimOutcome.CONFIRMED);
        }
        return results;
    }

    // ---------------------------------------------------------------- 孤儿预扣回补（对账，一个事务）

    /**
     * 回补一笔孤儿预扣（spec User Stories 14–16，对账 job 的库存侧动作）：**一个事务**——
     * <pre>
     * BEGIN
     *   UPDATE draw_strategy_award_inventory SET waiting/issued -= q, surplus += q（按 type 分支，带守卫）
     *   DELETE FROM draw_strategy_award_inventory_detail WHERE (award_id, flow_type=1, biz_type, biz_id)
     * COMMIT
     * </pre>
     * 两者必须同事务：守恒判据 B3（{@code waiting + issued = Σ flow_type=1 quantity}）——台账转回
     * 而明细行还在（或反之）即破等式。守卫（{@code waiting_qty >= q} / {@code issued_qty >= q}）
     * 不过 = 已回补过（前次部分成功的重放）或不变量破坏：返回 false 整体不动，日志由
     * {@code InventoryQuotaServiceImpl} 统一打 ERROR 留人工核对。账户类型由台账行现读。
     */
    public boolean restitutePrereserve(String awardId, String bizType, String bizId, long quantity) {
        Boolean restituted = transactionTemplate.execute(status -> {
            AwardInventoryPO ledger = awardInventoryDao.selectByAwardId(awardId);
            if (ledger == null) {
                // 台账行缺失：预扣明细在而账户不在 = 不变量破坏，不动、留给人工
                log.error("孤儿预扣回补失败：台账行缺失（不变量破坏） awardId={} bizType={} bizId={}",
                        awardId, bizType, bizId);
                return Boolean.FALSE;
            }
            // 台账 type 列存枚举 name（allocateIn 写入口径），valueOf 反查；脏值炸出来好过静默错分支
            int restored = switch (AwardInventoryType.valueOf(ledger.getType())) {
                case PHYSICAL -> awardInventoryDao.restitutePhysical(awardId, quantity);
                case VIRTUAL -> awardInventoryDao.restituteVirtual(awardId, quantity);
            };
            if (restored == 0) {
                // 守卫不过：已回补过或不变量破坏。不动明细行（B3 不因这次调用破坏），返回 false
                return Boolean.FALSE;
            }
            return awardInventoryDetailDao.deleteDeductDetail(awardId, bizType, bizId) > 0;
        });
        return Boolean.TRUE.equals(restituted);
    }

    // ---------------------------------------------------------------- PO → 公共形状

    /** 预扣明细（{@code flow_type = 1}） */
    private AwardInventoryDetailPO toDeductDetailPO(String awardId, InventoryDeductRequestVO request) {
        AwardInventoryDetailPO detailPO = new AwardInventoryDetailPO();
        detailPO.setAwardId(awardId);
        detailPO.setQuantity(request.quantity());
        detailPO.setBizType(request.bizType());
        detailPO.setBizId(request.bizId());
        return detailPO;
    }

    /** 兑换确认明细（{@code flow_type = 2}） */
    private AwardInventoryDetailPO toClaimDetailPO(InventoryClaimRequestVO item) {
        AwardInventoryDetailPO detailPO = new AwardInventoryDetailPO();
        detailPO.setAwardId(item.awardId());
        detailPO.setQuantity(item.quantity());
        detailPO.setBizType(item.bizType());
        detailPO.setBizId(item.bizId());
        return detailPO;
    }
}
