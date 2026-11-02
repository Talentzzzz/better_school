package com.betterschool.marketing.infrastructure.inventory.adapter.repository;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.infrastructure.inventory.dao.IPhysicalQuotaDao;
import com.betterschool.marketing.infrastructure.inventory.dao.IVirtualInventoryQuotaDao;
import com.betterschool.marketing.infrastructure.inventory.dao.po.PhysicalMarketingQuotaDetailPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.PhysicalMarketingQuotaPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.VirtualInventoryQuotaDetailPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.VirtualInventoryQuotaPO;
import com.betterschool.marketing.infrastructure.inventory.merge.IQuotaMergeFlushHandler;
import com.betterschool.marketing.types.inventory.Constants;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存域唯一仓储实现：两段各碰自己的 DAO（PO / mapper XML 仍是两套），
 * 对外只呈现一套按 {@link InventorySegmentVO} 分派的方法。分派全部集中在 {@code switch} 表达式里。
 * <p>
 * 事务：{@link #flushDeductGroup}、{@link #flushClaimGroup}（各一组一个事务）
 * 是「一次操作的原子边界」，由本类用 {@code TransactionTemplate}（REQUIRED）包住；
 * 其余单步方法各是一条 SQL，不额外开事务。
 * <p>
 * 本类同时是合并器的落库出口（{@link IQuotaMergeFlushHandler}）：
 * 预扣的一组 = {@link #flushDeductGroup}（票 06 接线）；确认的一组 = {@link #flushClaimGroup}
 * （票 07 接线）——确认与预扣共用同一个 {@code QuotaMergeBuffer} 实例与分片，
 * 但两个组方法各自一个事务，两个失败域不混（spec §4.5）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class InventoryRepository implements IInventoryRepository, IQuotaMergeFlushHandler {

    private final IVirtualInventoryQuotaDao virtualInventoryQuotaDao;
    private final IPhysicalQuotaDao physicalQuotaDao;
    private final TransactionTemplate transactionTemplate;

    // ---------------------------------------------------------------- 台账

    @Override
    public InventoryLedgerVO queryLedger(InventorySegmentVO segment, String invId) {
        return switch (segment) {
            case VIRTUAL -> toVirtualLedger(virtualInventoryQuotaDao.selectInventory(invId));
            case QUOTA -> toQuotaLedger(physicalQuotaDao.selectQuota(invId));
        };
    }

    @Override
    public List<InventoryLedgerVO> listOpenLedgers(InventorySegmentVO segment) {
        return switch (segment) {
            case VIRTUAL -> virtualInventoryQuotaDao.selectOpenInventories().stream().map(this::toVirtualLedger).toList();
            case QUOTA -> physicalQuotaDao.selectOpenQuotas().stream().map(this::toQuotaLedger).toList();
        };
    }

    // ---------------------------------------------------------------- 预扣落库（一组一个事务，票 06）

    /**
     * 一组一个事务（spec §4.1–§4.3），形状与参考工程一致但多一条「探一次再切」：
     * <pre>
     * BEGIN
     *   INSERT IGNORE INTO &lt;segment&gt;_detail (...) VALUES (...), (...)   -- 流水先行，唯一键裁决幂等
     *   UPDATE &lt;segment&gt;_ledger SET surplus -= N, waiting/issued += N
     *    WHERE inv_id = ? AND (surplus &lt; 0 OR surplus &gt;= N)
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
    @Override
    public List<DeductResult> flushDeductGroup(InventorySegmentVO segment, String invId,
                                               List<InventoryDeductRequestVO> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            DeductFlush first = transactionTemplate.execute(status -> {
                // 撞键快照必须先于批量 INSERT：同事务刚插的行算「本事务新扣」，不能进剔除集
                Set<Integer> duplicates = findDuplicates(segment, requests);
                // 批内同业务键（同 token 双击落进同一组）：第二笔是第一笔的重放，同样剔除
                markIntraBatchDuplicates(requests, duplicates);
                int expectedNew = requests.size() - duplicates.size();
                int inserted = insertDeductDetails(segment, invId, requests);
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
                if (deductLedger(segment, invId, deductQty) == 0) {
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
            Long surplus = selectSurplus(segment, invId);
            if (surplus == null) {
                // 台账行缺失：插了流水扣不了账 = 不变量破坏，让整组以失败收场（合并器补 FAILED）
                throw new IllegalStateException("预扣落库台账行缺失（不变量破坏） segment=" + segment.getCode()
                        + " invId=" + invId);
            }
            Cut cut = cutByRemaining(requests, duplicates, surplus);
            if (cut.indexes().isEmpty()) {
                // k = 0：整批 INSUFFICIENT（重放件仍按成功回放）
                return perItemResults(requests.size(), duplicates, Set.of());
            }
            boolean committed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                List<InventoryDeductRequestVO> cutRequests = cut.requests(requests);
                // 第二事务同样先取撞键快照：截出来的件理应全是新件，命中即被并发重放抢先 ⇒ 整组回滚
                boolean raced = !findDuplicates(segment, cutRequests).isEmpty();
                int inserted = raced ? 0 : insertDeductDetails(segment, invId, cutRequests);
                if (raced || inserted < cutRequests.size()) {
                    // 探完到落库之间被并发消耗 / 抢落：整组回滚，宁少扣不多扣
                    status.setRollbackOnly();
                    return false;
                }
                if (deductLedger(segment, invId, totalQuantity(cutRequests)) == 0) {
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
                + " segment=" + segment.getCode() + " invId=" + invId + " 件数=" + requests.size());
    }

    @Override
    public boolean existsDeductDetail(InventorySegmentVO segment, String invId, String bizType, String bizId) {
        return switch (segment) {
            case VIRTUAL -> virtualInventoryQuotaDao.existsDeductDetail(invId, bizType, bizId);
            // 实物只认 type = 1（预扣结转）的行：type = 2 是兑换确认，不是同一笔预扣
            case QUOTA -> physicalQuotaDao.existsDeductDetail(invId, Constants.DETAIL_TYPE_DEDUCT, bizType, bizId);
        };
    }

    /** 批量 {@code INSERT IGNORE}：新代码不写 {@code state}（列默认 0）与 {@code bucket_id}（桶层已删） */
    private int insertDeductDetails(InventorySegmentVO segment, String invId, List<InventoryDeductRequestVO> requests) {
        return switch (segment) {
            case VIRTUAL -> virtualInventoryQuotaDao.batchInsertIgnoreDeductDetails(invId,
                    requests.stream().map(r -> toVirtualDeductPO(invId, r)).toList());
            case QUOTA -> physicalQuotaDao.batchInsertIgnoreDeductDetails(invId,
                    requests.stream().map(r -> toQuotaDeductPO(invId, r)).toList());
        };
    }

    /** 回查批内已存在的业务行：命中下标集合 + 命中量（它们的量在更早的事务里已经扣过） */
    private Set<Integer> findDuplicates(InventorySegmentVO segment, List<InventoryDeductRequestVO> requests) {
        // 按 bizType 分组回查（唯一键的前缀），同组 bizType 恒一致，这里只为防混批
        Map<String, List<String>> byBizType = new LinkedHashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            InventoryDeductRequestVO request = requests.get(i);
            byBizType.computeIfAbsent(request.bizType(), key -> new ArrayList<>()).add(request.bizId());
        }
        Set<String> existingBizIds = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : byBizType.entrySet()) {
            List<String> bizIds = entry.getValue();
            switch (segment) {
                case VIRTUAL -> virtualInventoryQuotaDao.listExistingDeductDetails(entry.getKey(), bizIds)
                        .forEach(po -> existingBizIds.add(po.getBizId()));
                case QUOTA -> physicalQuotaDao.listExistingDeductDetails(entry.getKey(), bizIds)
                        .forEach(po -> existingBizIds.add(po.getBizId()));
            }
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

    private Long selectSurplus(InventorySegmentVO segment, String invId) {
        return switch (segment) {
            case VIRTUAL -> virtualInventoryQuotaDao.selectSurplus(invId);
            case QUOTA -> physicalQuotaDao.selectSurplus(invId);
        };
    }

    private int deductLedger(InventorySegmentVO segment, String invId, long quantity) {
        return switch (segment) {
            case VIRTUAL -> virtualInventoryQuotaDao.deductLedger(invId, quantity);
            case QUOTA -> physicalQuotaDao.deductLedger(invId, quantity);
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
    private record DeductFlush(List<DeductResult> results, Set<Integer> duplicates,
                               boolean needRetry, boolean staleSnapshot) {

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
    }

    /** 「探一次再切」截出来的件：组内下标（回填用）+ 截取后的子序列（落库用） */
    private record Cut(List<Integer> indexes) {

        Set<Integer> originalIndexes() {
            return new HashSet<>(indexes);
        }

        List<InventoryDeductRequestVO> requests(List<InventoryDeductRequestVO> all) {
            return indexes.stream().map(all::get).toList();
        }
    }

    // ---------------------------------------------------------------- 合并器落库出口（确认，票 07 接线）

    /**
     * 兑换确认的一组（spec §4.5），一个事务：
     * <pre>
     * BEGIN
     *   INSERT IGNORE INTO physical_marketing_quota_detail (...)   -- type = 2，逐行不批插
     *   UPDATE physical_marketing_quota
     *     SET waiting_qty -= c, issued_qty += c                    -- c = 本次新增行数
     *    WHERE inv_id = ? AND waiting_qty &gt;= c
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
    @Override
    public List<ClaimOutcome> flushClaimGroup(String invId, List<ClaimItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        transactionTemplate.executeWithoutResult(status -> {
            long transferQty = 0;
            for (ClaimItem item : items) {
                if (physicalQuotaDao.insertIgnoreClaimDetail(toClaimDetailPO(invId, item)) > 0) {
                    // 只有新增行产生转移：撞键回放的行，它的量在更早的事务里已经转过了
                    transferQty += item.quantity();
                }
            }
            if (transferQty == 0) {
                // 整组撞 uk_biz_type = 都已确认过：不转移、直接提交、按成功回放
                return;
            }
            if (physicalQuotaDao.transferWaitingToIssued(invId, transferQty) == 0) {
                log.error("兑换确认守卫不过（waiting_qty < 转移量，不变量破坏，整组回滚）"
                        + " invId={} 转移量={} 件数={}", invId, transferQty, items.size());
                throw new IllegalStateException("兑换确认 waiting 不足（不变量破坏，整组回滚） invId="
                        + invId + " 转移量=" + transferQty + " 件数=" + items.size());
            }
        });
        List<ClaimOutcome> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            results.add(ClaimOutcome.CONFIRMED);
        }
        return results;
    }

    // ---------------------------------------------------------------- PO → 公共形状

    /** 虚拟段没有 waiting 列：这里显式补齐 0，让两段共用 {@code waiting + issued} 的对账表达 */
    private InventoryLedgerVO toVirtualLedger(VirtualInventoryQuotaPO po) {
        if (po == null) {
            return null;
        }
        return new InventoryLedgerVO(po.getInvId(), po.getSurplusQty(), 0L, po.getIssuedQty(), po.getState());
    }

    private InventoryLedgerVO toQuotaLedger(PhysicalMarketingQuotaPO po) {
        if (po == null) {
            return null;
        }
        return new InventoryLedgerVO(po.getInvId(), po.getSurplusQty(), po.getWaitingQty(),
                po.getIssuedQty(), po.getState());
    }

    /** 预扣结转明细（{@code type = 1}，不写 bucket_id / state——桶层已删，state 落列默认 0） */
    private PhysicalMarketingQuotaDetailPO toQuotaDeductPO(String invId, InventoryDeductRequestVO request) {
        PhysicalMarketingQuotaDetailPO detailPO = new PhysicalMarketingQuotaDetailPO();
        detailPO.setInvId(invId);
        detailPO.setQuantity(request.quantity());
        detailPO.setType(Constants.DETAIL_TYPE_DEDUCT);
        detailPO.setBizType(request.bizType());
        detailPO.setBizId(request.bizId());
        return detailPO;
    }

    /** 兑换确认明细（{@code type = 2}，不写 bucket_id / state——桶层已删，state 落列默认 0） */
    private PhysicalMarketingQuotaDetailPO toClaimDetailPO(String invId, ClaimItem item) {
        PhysicalMarketingQuotaDetailPO detailPO = new PhysicalMarketingQuotaDetailPO();
        detailPO.setInvId(invId);
        detailPO.setQuantity(item.quantity());
        detailPO.setType(Constants.DETAIL_TYPE_CLAIM);
        detailPO.setBizType(item.bizType());
        detailPO.setBizId(item.bizId());
        return detailPO;
    }

    private VirtualInventoryQuotaDetailPO toVirtualDeductPO(String invId, InventoryDeductRequestVO request) {
        VirtualInventoryQuotaDetailPO detailPO = new VirtualInventoryQuotaDetailPO();
        detailPO.setInvId(invId);
        detailPO.setQuantity(request.quantity());
        detailPO.setBizType(request.bizType());
        detailPO.setBizId(request.bizId());
        return detailPO;
    }
}
