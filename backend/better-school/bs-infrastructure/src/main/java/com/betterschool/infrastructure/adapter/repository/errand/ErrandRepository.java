package com.betterschool.infrastructure.adapter.repository.errand;

import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandFeedCursorVO;
import com.betterschool.domain.errand.model.valobj.ErrandFeedIndexItemVO;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.errand.model.valobj.ErrandTypeVO;
import com.betterschool.domain.errand.model.valobj.ErrandPaidMessage;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.search.model.valobj.SearchEntityType;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.infrastructure.adapter.repository.moderation.ReviewTaskPoolStore;
import com.betterschool.infrastructure.adapter.merge.CountAction;
import com.betterschool.infrastructure.adapter.merge.CountEvent;
import com.betterschool.infrastructure.adapter.merge.LikeState;
import com.betterschool.infrastructure.adapter.merge.ViewMergeWriter;
import com.betterschool.infrastructure.dao.errand.IErrandDao;
import com.betterschool.infrastructure.dao.errand.IErrandFeedIndexDao;
import com.betterschool.infrastructure.dao.errand.IErrandOrderEventDao;
import com.betterschool.infrastructure.dao.errand.IErrandViewCountDao;
import com.betterschool.infrastructure.dao.errand.IErrandViewHistoryDao;
import com.betterschool.infrastructure.dao.errand.po.ErrandFeedIndexRow;
import com.betterschool.infrastructure.dao.errand.po.ErrandOrderEvent;
import com.betterschool.infrastructure.dao.errand.po.ErrandOrder;
import com.betterschool.infrastructure.dao.moderation.IContentReviewDao;
import com.betterschool.infrastructure.dao.moderation.po.ContentReview;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.User;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ErrandRepository implements IErrandRepository {

    private final IErrandDao errandDao;
    /** 订单状态流转事件表（errand_order_event 单表，原 IErrandDao 拆分） */
    private final IErrandOrderEventDao errandOrderEventDao;
    /** 大厅 feed 异构索引表（errand_feed_index 单表，原 IErrandDao 拆分） */
    private final IErrandFeedIndexDao errandFeedIndexDao;
    /** 浏览 UV 计数窄表（errand_view_count 单表，原 IErrandDao 拆分） */
    private final IErrandViewCountDao errandViewCountDao;
    /** 浏览 UV 明细表（errand_view_history 单表，原 IErrandDao 拆分） */
    private final IErrandViewHistoryDao errandViewHistoryDao;
    private final ViewMergeWriter viewMergeWriter;
    private final OutboxMessageStore outboxMessageStore;
    private final ITimeoutRepository timeoutRepository;
    private final ReviewTaskPoolStore reviewTaskPoolStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    /** 发布方/接单方昵称头像批量回查（SQL 层零 JOIN：订单列表/详情的 bs_user 信息在这里多查一次拼装） */
    private final IUserDao userDao;
    /** feed 索引重建取 APPROVE 审批流水（content_review 单表，IContentReviewDao 内该语句零 JOIN） */
    private final IContentReviewDao contentReviewDao;

    @Override
    public void save(ErrandOrderEntity orderEntity) {
        // 业务 ID 应用层发号、insert 前一次写入（uuid-biz-id 票 05：物理自增 id 只作内部键，不再同值回写）
        orderEntity.setOrderId(UUID.randomUUID().toString());
        ErrandOrder order = toPO(orderEntity);
        errandDao.insert(order);
        // 创建事件：from_state 为 NULL（sql.md 口径），操作方恒发布方
        saveEvent(order.getErrandOrderId(), "CREATED", null, order.getState(),
                order.getPublisherId(), "PUBLISHER", null);
    }

    @Override
    public ErrandOrderEntity findById(String orderId) {
        ErrandOrder row = errandDao.selectById(orderId);
        fillOrderExtras(row == null ? List.of() : List.of(row));
        return toEntity(row);
    }

    @Override
    public List<ErrandOrderEntity> pageList(String type, String status, String sort, int offset, int limit) {
        // hot 档排序键 view_count 在窄表（SQL 不能 JOIN 进来）：公开口径全集 + 窄表批量回查 + 内存排序切片。
        // 旧直查路径仅作降级（索引不可用/带筛选时），数据集有界，可接受
        List<ErrandOrder> errandOrderList;
        if ("hot".equals(sort)) {
            List<ErrandOrder> allVisibleErrandOrderList = errandDao.selectVisibleRows(type, status);
            Map<String, ErrandOrder> viewRows = viewCountRowsById(errandViewCountDao.selectViewCountsByOrderIds(
                    allVisibleErrandOrderList.stream().map(ErrandOrder::getErrandOrderId).toList()));
            errandOrderList = allVisibleErrandOrderList.stream()
                    .sorted(Comparator
                            .comparing((ErrandOrder o) -> viewCountOf(viewRows, o.getErrandOrderId())).reversed()
                            .thenComparing(ErrandOrder::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(ErrandOrder::getErrandOrderId, Comparator.reverseOrder()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        } else {
            errandOrderList = errandDao.selectPage(type, status, sort, offset, limit);
        }
        fillOrderExtras(errandOrderList);
        return errandOrderList.stream().map(this::toEntity).toList();
    }

    @Override
    public long count(String type, String status) {
        return errandDao.count(type, status);
    }

    @Override
    public List<ErrandFeedIndexItemVO> scanFeedIndex(String sortType, ErrandFeedCursorVO cursor, int limit) {
        return errandFeedIndexDao.selectFeedIndex(sortType, cursor, limit);
    }

    /**
     * feed 索引全量重建（market-feed-uv 票 06，照 {@code CommunityRepository.rebuildFeedIndex} 先例）：
     * 四条语句一个事务——计数窄表校准（ODKU 补缺行 / 刷漂移 + 清孤儿）→ 清空索引 → 重灌投影。
     * 顺序是先校准后清灌：计数窄表是索引热度键的数据源，先算准再抄进重灌语句，一个事务里两次写只对
     * 读者呈现前后两个完整状态（清空用 DELETE 不用 TRUNCATE，后者隐式提交会毁掉原子性）
     */
    @Override
    public void rebuildFeedIndex() {
        transactionTemplate.executeWithoutResult(status -> {
            int recalibrated = errandViewCountDao.recalibrateViewCounts();
            int orphans = errandViewCountDao.deleteViewCountsMissingHistory();
            errandFeedIndexDao.clearFeedIndex();
            int rebuilt = rebuildFeedIndexRows();
            log.info("[MarketFeedIndexRebuild] errand_feed_index 清空重灌 {} 行；errand_view_count 校准 {} 行、清理孤儿 {} 行",
                    rebuilt, recalibrated, orphans);
        });
    }

    /**
     * 重灌行集拼装（原 INSERT..SELECT 一步投影的多查形态）：行集 = PUBLISHED 订单 ∪ 有过审批通过的订单
     * （行常驻口径：通过事务才诞生行、之后 13 态流转只翻快照、行永不删除）。三段取数——
     * ① errand_order 全量 (errand_order_id, state, create_time, reward)；② content_review 的 APPROVE 流水
     * （feed_time 真源，经 IContentReviewDao 单表 GROUP BY，查不到审批流水取 create_time 兜底）；
     * ③ view_count 从计数窄表抄入（无行 = 0）——组装后批量写回
     */
    private int rebuildFeedIndexRows() {
        Map<String, LocalDateTime> approveTimes = new HashMap<>();
        for (ContentReview approveRow : contentReviewDao.selectApproveRowsByTargetType(ReviewTargetTypeVO.ERRAND.name())) {
            approveTimes.put(approveRow.getTargetId(), approveRow.getCreateTime());
        }
        List<ErrandFeedIndexRow> feedIndexRowList = new ArrayList<>();
        for (ErrandFeedIndexRow candidate : errandDao.selectRebuildRows()) {
            boolean approved = approveTimes.containsKey(candidate.getErrandOrderId());
            if (!ErrandStatusVO.PUBLISHED.name().equals(candidate.getState()) && !approved) {
                // 审核通过前的草稿/待支付/待审/驳回订单不凭空进索引
                continue;
            }
            candidate.setFeedTime(approveTimes.getOrDefault(candidate.getErrandOrderId(), candidate.getCreateTime()));
            feedIndexRowList.add(candidate);
        }
        if (!feedIndexRowList.isEmpty()) {
            Map<String, ErrandOrder> viewRows = viewCountRowsById(errandViewCountDao.selectViewCountsByOrderIds(
                    feedIndexRowList.stream().map(ErrandFeedIndexRow::getErrandOrderId).toList()));
            for (ErrandFeedIndexRow row : feedIndexRowList) {
                row.setViewCount(viewCountOf(viewRows, row.getErrandOrderId()));
            }
            for (int from = 0; from < feedIndexRowList.size(); from += REBUILD_INSERT_BATCH) {
                errandFeedIndexDao.insertFeedIndexRows(feedIndexRowList.subList(from, Math.min(from + REBUILD_INSERT_BATCH, feedIndexRowList.size())));
            }
        }
        return feedIndexRowList.size();
    }

    @Override
    public boolean payById(String orderId, String actorId, int timeLimitHours) {
        // 先付后审：支付进入待审（处于支付事务内，轮次恒为 1）
        boolean updated = errandDao.payById(orderId, timeLimitHours) > 0;
        if (updated) {
            saveEvent(orderId, "PAID", "PENDING_PAYMENT", "PENDING_REVIEW", actorId, "PUBLISHER", null);
        }
        return updated;
    }

    @Override
    public boolean grab(String orderId, String takerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.grab(orderId, takerId) > 0;
            if (updated) {
                // 事件类型 = LOCKED（抢单，抢 ≠ 开工）；勿与「确认接单」的 CONFIRMED 撞名
                saveEvent(orderId, "LOCKED", "PUBLISHED", "LOCKED", takerId, "TAKER", null);
                // 索引行同事务翻 LOCKED（行必存在：LOCKED 只可从 PUBLISHED 可达，行诞生于审核通过）
                syncFeedIndexState(orderId, ErrandStatusVO.LOCKED);
            }
            return updated;
        }));
    }

    @Override
    public boolean acceptLockedById(String orderId, String takerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.acceptLockedById(orderId, takerId) > 0;
            if (updated) {
                saveEvent(orderId, "CONFIRMED", "LOCKED", "CONFIRMED", takerId, "TAKER", null);
                syncFeedIndexState(orderId, ErrandStatusVO.CONFIRMED);
            }
            return updated;
        }));
    }

    @Override
    public boolean abandonLockedById(String orderId, String takerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.abandonLockedById(orderId, takerId) > 0;
            if (updated) {
                // 放弃：独立事件 LOCK_ABANDONED（原因 TAKER_ABANDON）；不扣信用分、不动 taker_canceled
                saveEvent(orderId, "LOCK_ABANDONED", "LOCKED", "PUBLISHED", takerId, "TAKER", "TAKER_ABANDON");
                // 索引行同事务翻回 PUBLISHED（重新进大厅可见）
                syncFeedIndexState(orderId, ErrandStatusVO.PUBLISHED);
            }
            return updated;
        }));
    }

    @Override
    public int lockSeqOf(String orderId) {
        return errandDao.selectLockSeqById(orderId);
    }

    @Override
    public int countRecentAbandons(String userId, LocalDateTime since) {
        return errandOrderEventDao.countAbandonsSince(userId, since);
    }

    @Override
    public LocalDateTime latestAbandonTime(String userId, LocalDateTime since) {
        return errandOrderEventDao.selectLatestAbandonTimeSince(userId, since);
    }

    @Override
    public boolean releaseLockedByTimeout(String orderId, int lockSeq) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (errandDao.releaseLockedByTimeout(orderId, lockSeq) == 0) {
                // 陈旧轮次 / 已确认 / 已放弃 / 已取消：幂等成功、零副作用
                return false;
            }
            // 释放事件与条件 UPDATE 同事务（轮次守卫命中即「本轮锁单确实被释放」）
            saveEvent(orderId, "LOCK_TIMEOUT", "LOCKED", "PUBLISHED", null, "SYSTEM", null);
            // 索引行同事务翻回 PUBLISHED（重新进大厅可见）
            syncFeedIndexState(orderId, ErrandStatusVO.PUBLISHED);
            return true;
        }));
    }

    @Override
    public boolean pickupById(String orderId, String takerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.pickupById(orderId, takerId) > 0;
            if (updated) {
                saveEvent(orderId, "PICKED_UP", "CONFIRMED", "PICKED_UP", takerId, "TAKER", null);
                syncFeedIndexState(orderId, ErrandStatusVO.PICKED_UP);
            }
            return updated;
        }));
    }

    @Override
    public boolean deliverById(String orderId, String takerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.deliverById(orderId, takerId) > 0;
            if (updated) {
                saveEvent(orderId, "DELIVERED", "PICKED_UP", "DELIVERED", takerId, "TAKER", null);
                syncFeedIndexState(orderId, ErrandStatusVO.DELIVERED);
            }
            return updated;
        }));
    }

    @Override
    public boolean confirmById(String orderId, String publisherId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.confirmById(orderId, publisherId) > 0;
            if (updated) {
                // 事件名 = 发布方确认完成（COMPLETED），与自动完成的 AUTO_COMPLETED 区分；
                // 不再沿用旧名 CONFIRMED（已让位给「已确认」状态，避免撞名）
                saveEvent(orderId, "COMPLETED", "DELIVERED", "COMPLETED", publisherId, "PUBLISHER", null);
                syncFeedIndexState(orderId, ErrandStatusVO.COMPLETED);
            }
            return updated;
        }));
    }

    @Override
    public boolean cancelByPublisher(String orderId, String publisherId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 取消允许 PENDING_PAYMENT/PENDING_REVIEW/PUBLISHED/CONFIRMED 四个前态，前态要落事件必须先读
            String fromState = priorStateOf(orderId);
            boolean updated = errandDao.cancelByPublisher(orderId, publisherId) > 0;
            if (updated) {
                saveEvent(orderId, "CANCELLED", fromState, "CANCELLED",
                        publisherId, "PUBLISHER", "PUBLISHER_CANCEL");
                // 索引行同事务不可见化：从待审取消的行不存在（行诞生于审核通过），0 行静默
                syncFeedIndexState(orderId, ErrandStatusVO.CANCELLED);
            }
            return updated;
        }));
    }

    @Override
    public boolean cancelByTaker(String orderId, String takerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.cancelByTaker(orderId, takerId) > 0;
            if (updated) {
                saveEvent(orderId, "TAKER_CANCELLED", "CONFIRMED", "PUBLISHED", takerId, "TAKER", "TAKER_CANCEL");
                // 索引行同事务翻回 PUBLISHED（重新进大厅可见）
                syncFeedIndexState(orderId, ErrandStatusVO.PUBLISHED);
            }
            return updated;
        }));
    }

    @Override
    public boolean disputeById(String orderId, String actorId, String actorRole) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 争议允许 CONFIRMED/PICKED_UP/DELIVERED 三个前态，前态要落事件必须先读
            String fromState = priorStateOf(orderId);
            boolean updated = errandDao.disputeById(orderId) > 0;
            if (updated) {
                saveEvent(orderId, "DISPUTED", fromState, "DISPUTED", actorId, actorRole, "DISPUTE");
                syncFeedIndexState(orderId, ErrandStatusVO.DISPUTED);
            }
            return updated;
        }));
    }

    @Override
    public boolean arbitrateCancelById(String orderId, String operatorId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.arbitrateCancelById(orderId) > 0;
            if (updated) {
                saveEvent(orderId, "ARBITRATED", "DISPUTED", "CANCELLED", operatorId, "ADMIN", "ARBITRATION");
                syncFeedIndexState(orderId, ErrandStatusVO.CANCELLED);
            }
            return updated;
        }));
    }

    @Override
    public boolean arbitrateCompleteById(String orderId, String operatorId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.arbitrateCompleteById(orderId) > 0;
            if (updated) {
                saveEvent(orderId, "ARBITRATED", "DISPUTED", "COMPLETED", operatorId, "ADMIN", "ARBITRATION");
                syncFeedIndexState(orderId, ErrandStatusVO.COMPLETED);
            }
            return updated;
        }));
    }

    @Override
    public boolean markTimeout(String orderId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 超时允许 PUBLISHED/CONFIRMED/PICKED_UP 三个前态，前态要落事件必须先读
            String fromState = priorStateOf(orderId);
            boolean updated = errandDao.markTimeout(orderId) > 0;
            if (updated) {
                saveEvent(orderId, "TIMEOUT", fromState, "CANCELLED", null, "SYSTEM", null);
                syncFeedIndexState(orderId, ErrandStatusVO.CANCELLED);
            }
            return updated;
        }));
    }

    @Override
    public boolean cancelPendingPayment(String orderId) {
        boolean updated = errandDao.cancelPendingPayment(orderId) > 0;
        if (updated) {
            saveEvent(orderId, "PAY_TIMEOUT", "PENDING_PAYMENT", "CANCELLED", null, "SYSTEM", null);
        }
        return updated;
    }

    @Override
    public List<String> listPayTimeoutOrderIds(LocalDateTime before, int limit) {
        return errandDao.selectPayTimeoutOrderIds(before, limit);
    }

    @Override
    public List<String> listTimeoutOrderIds(int limit) {
        return errandDao.selectTimeoutOrderIds(limit);
    }

    @Override
    public List<String> listPendingReviewOrderIds(LocalDateTime before, int limit) {
        return errandDao.selectPendingReviewTimeoutOrderIds(before, limit);
    }

    @Override
    public boolean reviewApproveById(String orderId, String operatorId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.reviewApproveById(orderId) > 0;
            if (updated) {
                saveEvent(orderId, "REVIEW_APPROVED", "PENDING_REVIEW", "PUBLISHED", operatorId, "ADMIN", null);
                // feed 索引行诞生（同事务，唯一诞生点）：feed_time = 进入大厅时刻，price = 悬赏快照
                errandFeedIndexDao.upsertFeedIndexOnApprove(orderId);
            }
            return updated;
        }));
    }

    @Override
    public boolean reviewRejectById(String orderId, String operatorId, String reason) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 审核驳回：REJECTED 终态只可从待审可达，索引行尚未诞生（行诞生于审核通过），无需带写
            boolean updated = errandDao.reviewRejectById(orderId) > 0;
            if (updated) {
                saveEvent(orderId, "REVIEW_REJECTED", "PENDING_REVIEW", "REJECTED", operatorId, "ADMIN",
                        "REVIEW_REJECT", reason);
            }
            return updated;
        }));
    }

    @Override
    public boolean autoCompleteById(String orderId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = errandDao.autoCompleteById(orderId) > 0;
            if (updated) {
                saveEvent(orderId, "AUTO_COMPLETED", "DELIVERED", "COMPLETED", null, "SYSTEM", null);
                syncFeedIndexState(orderId, ErrandStatusVO.COMPLETED);
            }
            return updated;
        }));
    }

    @Override
    public List<String> listAutoCompleteOrderIds(LocalDateTime before, int limit) {
        return errandDao.selectAutoCompleteOrderIds(before, limit);
    }

    @Override
    public List<ErrandOrderEntity> listMyPublished(String userId, String status, int offset, int limit) {
        List<ErrandOrder> errandOrderList = errandDao.selectMyPublished(userId, status, offset, limit);
        fillOrderExtras(errandOrderList);
        return errandOrderList.stream().map(this::toEntity).toList();
    }

    @Override
    public long countMyPublished(String userId, String status) {
        return errandDao.countMyPublished(userId, status);
    }

    @Override
    public List<ErrandOrderEntity> listPublicByPublisher(String userId, int offset, int limit) {
        List<ErrandOrder> errandOrderList = errandDao.selectPublicPageByPublisher(userId, offset, limit);
        fillOrderExtras(errandOrderList);
        return errandOrderList.stream().map(this::toEntity).toList();
    }

    @Override
    public long countPublicByPublisher(String userId) {
        return errandDao.countPublicByPublisher(userId);
    }

    @Override
    public List<ErrandOrderEntity> listMyTaken(String userId, String status, int offset, int limit) {
        List<ErrandOrder> errandOrderList = errandDao.selectMyTaken(userId, status, offset, limit);
        fillOrderExtras(errandOrderList);
        return errandOrderList.stream().map(this::toEntity).toList();
    }

    @Override
    public long countMyTaken(String userId, String status) {
        return errandDao.countMyTaken(userId, status);
    }

    @Override
    public void schedulePayTimeout(String orderId, long delaySeconds) {
        // 业务事务内直插超时任务行（内嵌超时中心，注册 outbox 已删）：三分支幂等在超时仓储内裁决
        // （uuid-biz-id 票 05：orderId 即 UUID 字符串，biz_id 直传）；时刻一律数据库时钟，Java 只传时长
        timeoutRepository.register(TimeoutTaskEntity.newWaiting(
                Constants.ERRAND_PAY_TIMEOUT, orderId, delaySeconds));
    }

    @Override
    public void scheduleLockTimeout(String orderId, int lockSeq, long delaySeconds) {
        // 复合 bizId：给每次锁单一个独立键（陈旧任务只影响旧键，不会幂等复位新锁的到期时刻）；
        // 直插与锁单同事务，回滚即无行
        timeoutRepository.register(TimeoutTaskEntity.newWaiting(
                Constants.ERRAND_LOCK_TIMEOUT, orderId + ":" + lockSeq, delaySeconds));
    }

    @Override
    public List<ErrandOrderEntity> listByIds(List<String> orderIdList) {
        List<ErrandOrder> errandOrderList = errandDao.selectByIds(orderIdList);
        fillOrderExtras(errandOrderList);
        return errandOrderList.stream().map(this::toEntity).toList();
    }

    @Override
    public List<String> listIdsAfter(String lastId, int limit) {
        return errandDao.selectIdsAfter(lastId, limit);
    }

    @Override
    public void saveSearchSyncMessage(String orderId) {
        outboxMessageStore.save(Constants.SEARCH_SYNC_TOPIC,
                new SearchSyncMessage(SearchEntityType.ERRAND.name().toLowerCase(), orderId));
    }

    @Override
    public void saveErrandPaidMessage(String orderId) {
        outboxMessageStore.save(Constants.ERRAND_PAID_TOPIC, new ErrandPaidMessage(orderId));
    }

    // ---------- 原子用例：仓储方法即一次事务（REQUIRED，与既有事务合流） ----------

    @Override
    public void createWithPayTimeout(ErrandOrderEntity orderEntity, int payTimeoutMinutes) {
        transactionTemplate.execute(status -> {
            save(orderEntity);
            // "15 分钟未支付"超时任务与订单落库同事务（直插 timeout_task）：
            // Java 只传时长，到期时刻由 SQL 侧 TIMESTAMPADD 按数据库时钟落库
            schedulePayTimeout(orderEntity.getOrderId(), payTimeoutMinutes * 60L);
            return null;
        });
    }

    @Override
    public boolean grabWithLockTimeout(String orderId, String takerId, int lockTimeoutMinutes) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!grab(orderId, takerId)) {
                // 条件 UPDATE 未命中：不注册超时任务（本次没有产生锁单，不该有计时）
                return false;
            }
            // 复合 bizId 取「本次抢单后的轮次」：同事务内读，读到的就是本次 lock_seq+1 的值
            int lockSeq = errandDao.selectLockSeqById(orderId);
            // 计时起点 = 锁单时刻（与支付超时「从创建时刻起算」口径不同）；
            // Java 只传时长，到期时刻由 SQL 侧按数据库时钟落库
            scheduleLockTimeout(orderId, lockSeq, lockTimeoutMinutes * 60L);
            return true;
        }));
    }

    @Override
    public void saveDraft(ErrandOrderEntity orderEntity) {
        transactionTemplate.execute(status -> {
            save(orderEntity);
            return null;
        });
    }

    @Override
    public void updateDraft(ErrandOrderEntity orderEntity) {
        // 手工组装：草稿字段可为 null（toPO 会取 type.name()，不可用），且 UPDATE 只落内容列
        ErrandOrder order = new ErrandOrder();
        order.setErrandOrderId(orderEntity.getOrderId());
        order.setPublisherId(orderEntity.getPublisherId());
        order.setType(orderEntity.getType() == null ? null : orderEntity.getType().name());
        order.setDescription(orderEntity.getDescription());
        order.setPickupLocation(orderEntity.getPickupLocation());
        order.setDeliveryLocation(orderEntity.getDeliveryLocation());
        order.setReward(orderEntity.getReward());
        order.setTimeLimitHours(orderEntity.getTimeLimitHours());
        order.setImages(toImagesJson(orderEntity.getImages()));
        errandDao.updateDraft(order);
    }

    @Override
    public void submitDraftWithPayTimeout(String orderId, String publisherId, int payTimeoutMinutes) {
        transactionTemplate.execute(status -> {
            if (errandDao.submitDraft(orderId, publisherId) == 0) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            saveEvent(orderId, "SUBMITTED", "DRAFT", "PENDING_PAYMENT", publisherId, "PUBLISHER", null);
            // 支付超时任务与状态迁移同事务（草稿态不注册，提交即起钟）；
            // Java 只传时长，到期时刻由 SQL 侧按数据库时钟落库
            schedulePayTimeout(orderId, payTimeoutMinutes * 60L);
            return null;
        });
    }

    @Override
    public boolean softDeleteDraft(String orderId, String publisherId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (errandDao.softDelete(orderId, publisherId) == 0) {
                return false;
            }
            saveEvent(orderId, "DELETED", "DRAFT", "DELETED", publisherId, "PUBLISHER", null);
            return true;
        }));
    }

    @Override
    public void payWithPaidMessage(String orderId, String userId, int timeLimitHours) {
        transactionTemplate.execute(status -> {
            if (!payById(orderId, userId, timeLimitHours)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            saveErrandPaidMessage(orderId);
            // 入池点（spec §5.1）：支付成功进待审，工单开单与状态迁移同事务；
            // entered_at 由开单 SQL NOW() 落库（≈支付成功时刻，同事务同秒），轮次恒 1
            reviewTaskPoolStore.enter(ReviewTargetTypeVO.ERRAND, orderId, 1, userId);
            return null;
        });
    }

    @Override
    public void deliverByIdOrThrow(String orderId, String takerId) {
        transactionTemplate.execute(status -> {
            if (!deliverById(orderId, takerId)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            return null;
        });
    }

    @Override
    public void confirmWithSearchSync(String orderId, String publisherId) {
        transactionTemplate.execute(status -> {
            if (!confirmById(orderId, publisherId)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            // 完成后订单退出公开列表，落搜索同步消息（消费方删除 ES 文档）
            saveSearchSyncMessage(orderId);
            return null;
        });
    }

    @Override
    public void cancelByPublisherWithSearchSync(String orderId, String publisherId) {
        transactionTemplate.execute(status -> {
            if (!cancelByPublisher(orderId, publisherId)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            // 取消后订单退出公开列表（PENDING_PAYMENT 取消无文档，消息无害）
            saveSearchSyncMessage(orderId);
            // 待审单可被发布方取消：退出待审同事务出池（其余状态无在途工单，幂等 0 行无害）
            reviewTaskPoolStore.close(ReviewTargetTypeVO.ERRAND, orderId);
            return null;
        });
    }

    @Override
    public void disputeWithSearchSync(String orderId, String actorId, String actorRole) {
        transactionTemplate.execute(status -> {
            if (!disputeById(orderId, actorId, actorRole)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            // 仲裁中订单退出公开列表，落搜索同步消息（消费方删除 ES 文档）
            saveSearchSyncMessage(orderId);
            return null;
        });
    }

    @Override
    public boolean arbitrateWithSearchSync(String orderId, String operatorId, boolean cancel) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = cancel
                    ? arbitrateCancelById(orderId, operatorId)
                    : arbitrateCompleteById(orderId, operatorId);
            if (!updated) {
                return false;
            }
            // 仲裁结案退出公开列表，落搜索同步消息（消费方删除 ES 文档）
            saveSearchSyncMessage(orderId);
            return true;
        }));
    }

    @Override
    public boolean markTimeoutWithSearchSync(String orderId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!markTimeout(orderId)) {
                return false;
            }
            // 超时取消退出公开列表，落搜索同步消息
            saveSearchSyncMessage(orderId);
            return true;
        }));
    }

    @Override
    public boolean autoCompleteWithSearchSync(String orderId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!autoCompleteById(orderId)) {
                return false;
            }
            // 自动完成退出公开列表，落搜索同步消息
            saveSearchSyncMessage(orderId);
            return true;
        }));
    }

    private String toImagesJson(List<String> imageList) {
        if (imageList == null || imageList.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(imageList);
        } catch (Exception e) {
            throw new IllegalStateException("配图序列化失败", e);
        }
    }

    private List<String> toImagesList(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(imagesJson, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("配图反序列化失败，按空处理：{}", imagesJson, e);
            return List.of();
        }
    }

    private ErrandOrder toPO(ErrandOrderEntity orderEntity) {
        ErrandOrder order = new ErrandOrder();
        order.setErrandOrderId(orderEntity.getOrderId());
        order.setPublisherId(orderEntity.getPublisherId());
        order.setTakerId(orderEntity.getTakerId());
        order.setType(orderEntity.getType() == null ? null : orderEntity.getType().name());
        order.setDescription(orderEntity.getDescription());
        order.setPickupLocation(orderEntity.getPickupLocation());
        order.setDeliveryLocation(orderEntity.getDeliveryLocation());
        order.setReward(orderEntity.getReward());
        order.setTimeLimitHours(orderEntity.getTimeLimitHours());
        order.setImages(toImagesJson(orderEntity.getImages()));
        order.setState(orderEntity.getStatus().name());
        return order;
    }

    private ErrandOrderEntity toEntity(ErrandOrder order) {
        if (order == null) {
            return null;
        }
        ErrandOrderEntity orderEntity = new ErrandOrderEntity();
        orderEntity.setOrderId(order.getErrandOrderId());
        orderEntity.setPublisherId(order.getPublisherId());
        orderEntity.setTakerId(order.getTakerId());
        orderEntity.setType(order.getType() == null ? null : ErrandTypeVO.valueOf(order.getType()));
        orderEntity.setDescription(order.getDescription());
        orderEntity.setPickupLocation(order.getPickupLocation());
        orderEntity.setDeliveryLocation(order.getDeliveryLocation());
        orderEntity.setReward(order.getReward());
        orderEntity.setTimeLimitHours(order.getTimeLimitHours());
        orderEntity.setImages(toImagesList(order.getImages()));
        orderEntity.setDeadline(order.getDeadline());
        orderEntity.setStatus(ErrandStatusVO.valueOf(order.getState()));
        // 公开列表的浏览量由仓储从计数窄表批量回查带出（selectViewCountsByOrderIds）；其余查询不带
        // 该列（null → 0，详情由 orderDetail 回填）
        orderEntity.setViewCount(order.getViewCount() == null ? 0 : order.getViewCount());
        orderEntity.setTakerCanceled(order.getTakerCanceled() != null && order.getTakerCanceled());
        orderEntity.setCreatedAt(order.getCreateTime());
        orderEntity.setUpdatedAt(order.getUpdateTime());
        orderEntity.setPublisherNickname(order.getPublisherNickname());
        orderEntity.setPublisherAvatar(order.getPublisherAvatar());
        orderEntity.setTakerNickname(order.getTakerNickname());
        orderEntity.setTakerAvatar(order.getTakerAvatar());
        return orderEntity;
    }

    /** 订单状态流转事件落表（与条件 UPDATE 同事务，只插入；from_state 为 NULL 仅限创建事件） */
    private void saveEvent(String orderId, String eventType, String fromState, String toState,
                           String actorId, String actorRole, String reasonType) {
        saveEvent(orderId, eventType, fromState, toState, actorId, actorRole, reasonType, null);
    }

    // ---------- 拼装辅助：浏览窄表 / 昵称头像（SQL 层零 JOIN 的 Java 收口点） ----------

    /** 重灌行集批量写回的分片大小（防 foreach 语句过长） */
    private static final int REBUILD_INSERT_BATCH = 500;

    /**
     * 订单行补齐发布方/接单方昵称头像 + 浏览 UV 加速列：bs_user 与 errand_view_count 各一次 IN 批量回查。
     * 缺行补 0 / 补 null（与原 LEFT JOIN 合成口径一致）；列表与详情共用
     */
    private void fillOrderExtras(List<ErrandOrder> errandOrderList) {
        if (errandOrderList.isEmpty()) {
            return;
        }
        Map<String, ErrandOrder> viewRows = viewCountRowsById(errandViewCountDao.selectViewCountsByOrderIds(
                errandOrderList.stream().map(ErrandOrder::getErrandOrderId).toList()));
        List<String> userIdList = new ArrayList<>(errandOrderList.size() * 2);
        for (ErrandOrder row : errandOrderList) {
            userIdList.add(row.getPublisherId());
            userIdList.add(row.getTakerId());
        }
        Map<String, User> users = userDao.selectByIds(
                userIdList.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getUserId, user -> user, (a, b) -> a));
        for (ErrandOrder row : errandOrderList) {
            row.setViewCount(viewCountOf(viewRows, row.getErrandOrderId()));
            User publisher = users.get(row.getPublisherId());
            User taker = users.get(row.getTakerId());
            row.setPublisherNickname(publisher == null ? null : publisher.getNickname());
            row.setPublisherAvatar(publisher == null ? null : publisher.getAvatar());
            row.setTakerNickname(taker == null ? null : taker.getNickname());
            row.setTakerAvatar(taker == null ? null : taker.getAvatar());
        }
    }

    /** 部分列计数行 → orderId 索引 */
    private static Map<String, ErrandOrder> viewCountRowsById(List<ErrandOrder> viewCountRowList) {
        return viewCountRowList.stream().collect(Collectors.toMap(ErrandOrder::getErrandOrderId, row -> row, (a, b) -> a));
    }

    /** 窄表缺行 = 计数 0（与原 LEFT JOIN IFNULL(x, 0) 同口径） */
    private static Integer viewCountOf(Map<String, ErrandOrder> viewRows, String orderId) {
        ErrandOrder row = viewRows.get(orderId);
        return row == null ? 0 : row.getViewCount();
    }

    /**
     * 前态不唯一的条件 UPDATE 用：在 UPDATE **之前**读业务行 state，作为事件的前态。
     *
     * <p>from_state 的语义是「上一状态」，只有创建事件该为 NULL。多前态分支（发布方取消 4 前态、
     * 争议 3 前态、超时 3 前态）若也写 NULL，事件流就分不清「创建」与「从 CONFIRMED 取消」，纠纷溯源失真。
     * 必须在 UPDATE 之前读——更新后同一行的 state 已是新态。读到的值仅用于事件记录：
     * 状态机仍由条件 UPDATE 把关（命中 0 行则不落事件），故并发下最坏只是事件前态取了调用前观察值。
     */
    private String priorStateOf(String orderId) {
        ErrandOrder order = errandDao.selectById(orderId);
        return order == null ? null : order.getState();
    }

    /**
     * 订单状态流转事件落表（带原因补充说明）：reason 只在「用户/审核员输入了原因」的事件上非空
     * （当前仅审核驳回带枚举文案 + 补充说明），SYSTEM 事件恒 NULL——与 sql.md 列注释同口径
     */
    private void saveEvent(String orderId, String eventType, String fromState, String toState,
                           String actorId, String actorRole, String reasonType, String reason) {
        ErrandOrderEvent event = new ErrandOrderEvent();
        event.setOrderId(orderId);
        event.setEventType(eventType);
        event.setFromState(fromState);
        event.setToState(toState);
        event.setActorId(actorId);
        event.setActorRole(actorRole);
        event.setReasonType(reasonType);
        event.setReason(reason);
        errandOrderEventDao.insertOrderEvent(event);
    }

    // ---------- 计数合并器的落库方法：跑腿浏览（UV 口径，market-feed-uv 票 02） ----------

    @Override
    public void recordOrderView(String orderId, String userId) {
        // 浏览计数（UV 口径）：入队即返回，窗口过后一组一个事务——明细表 INSERT IGNORE + viewed_at
        // 刷新 + 加速列 += 新增数。匿名访问落不了明细行（errand_view_history 主键的一半是 user_id），
        // 按「INSERT 成功才算浏览」的口径不计入；明细行即真相源，失败只记丢弃数
        if (userId == null) {
            return;
        }
        viewMergeWriter.write(CountAction.ERRAND_VIEW, orderId, userId);
    }

    @Override
    public long viewCountOf(String orderId) {
        Integer count = errandViewCountDao.selectViewCount(orderId);
        return count == null ? 0 : count;
    }

    @Override
    public List<String> selectViewedOrderIds(String userId, List<String> orderIdList) {
        if (userId == null || orderIdList == null || orderIdList.isEmpty()) {
            return List.of();
        }
        return errandViewHistoryDao.selectViewedOrderIds(userId, orderIdList);
    }

    /**
     * 一组跑腿浏览（{@code TradeRepository.flushViewGroup} 的镜像）：一组一个事务三步——
     * <ol>
     *   <li>{@code INSERT IGNORE INTO errand_view_history}（影响行数 = 本组<b>真正新增</b>的 UV 数，
     *       重复浏览撞 {@code (user_id, errand_order_id)} 主键被裁决掉）；</li>
     *   <li>{@code UPDATE viewed_at}（本组全部键，含刚插入的——对重复浏览是唯一可见效果）；</li>
     *   <li>{@code errand_view_count += 新增数}（加速列恒等于明细行数）；</li>
     *   <li>{@code errand_feed_index.view_count += 新增数}（热度排序键旁路 ±N，market-feed-uv 票 03，
     *       命中 0 行落 warn 交重建 job）。</li>
     * </ol>
     *
     * <p><b>为什么计数按「影响行数」而不是「事件数」加</b>：前端双击、HTTP 重试、降级并发写都会产生
     * 重复键，{@code INSERT IGNORE} 的影响行数恰好是本组真正插入的行数，加速列与明细行条数就永远对得上。
     * 不重试、不回补：浏览允许丢，丢了下次浏览的 INSERT 会把缺的 UV 补上。
     *
     * <p><b>为什么要回一组占位结果</b>：浏览件没有业务结果（没有人在等），但引擎把「null / 缺位」
     * 当作漏回填并补 {@code FAILED}——那会让 {@code flushed} 漏掉这些件，合并比与
     * 「accepted − flushed」两个读数同时失真。
     */
    public List<LikeState> flushViewGroup(String orderIdKey, List<CountEvent> countEventList) {
        return transactionTemplate.execute(status -> {
            List<String> userIdList = distinctUserIds(countEventList);
            int inserted = errandViewHistoryDao.insertOrderViewHistory(orderIdKey, userIdList);
            errandViewHistoryDao.touchOrderViewedAt(orderIdKey, userIdList);
            if (inserted > 0) {
                errandViewCountDao.upsertOrderViewCount(orderIdKey, inserted);
                // 热度排序键旁路 ±N（market-feed-uv 票 03）：索引行 view_count 同事务跟上
                syncFeedIndexViewCount(orderIdKey, inserted);
            }
            return Collections.nCopies(countEventList.size(), LikeState.APPLIED);
        });
    }

    /**
     * feed 索引行状态快照（market-feed-uv 票 03）：条件 UPDATE 命中后同事务镜像 errand_order 的新状态
     * （行常驻 + 快照，大厅可见 = PUBLISHED）。纯 UPDATE 不 upsert——行不存在的常态（审核通过前的
     * 取消等）0 行静默，不在写路径凭空补行
     */
    private void syncFeedIndexState(String orderId, ErrandStatusVO state) {
        errandFeedIndexDao.updateFeedIndexState(orderId, state.name());
    }

    /**
     * feed 索引行热度键旁路 ±N（浏览没有取消语义，恒 +）：纯 UPDATE 不 upsert。命中 0 行 =
     * 索引行缺失（过审前被发布方浏览、或存量异常），落 warn 交重建 job（票 06），不在写路径补行
     */
    private void syncFeedIndexViewCount(String orderId, int delta) {
        if (errandFeedIndexDao.updateFeedIndexViewCount(orderId, delta) == 0) {
            log.warn("[ErrandRepository] 跑腿浏览旁路落 0 行：索引行缺失（未过审订单的浏览或存量异常），交重建 job 兜底 orderId={}", orderId);
        }
    }

    /** 一组内的去重浏览者（同组同人是重复件，明细表主键也只收得下一行） */
    private static List<String> distinctUserIds(List<CountEvent> countEventList) {
        Set<String> userIds = new LinkedHashSet<>(countEventList.size());
        for (CountEvent event : countEventList) {
            userIds.add(event.userId());
        }
        return new ArrayList<>(userIds);
    }

}
