package com.betterschool.domain.errand.service;

import com.betterschool.domain.errand.adapter.port.IErrandGrabAbusePort;
import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandArbitrateResult;
import com.betterschool.domain.errand.model.valobj.ErrandAutoCompleteResult;
import com.betterschool.domain.errand.model.valobj.ErrandCancelResult;
import com.betterschool.domain.errand.model.valobj.ErrandConfirmResult;
import com.betterschool.domain.errand.model.valobj.ErrandFeedCursorVO;
import com.betterschool.domain.errand.model.valobj.ErrandFeedIndexItemVO;
import com.betterschool.domain.errand.model.valobj.ErrandFeedPageVO;
import com.betterschool.domain.errand.model.valobj.ErrandIncomeVO;
import com.betterschool.domain.errand.model.valobj.ErrandLockTimeoutResult;
import com.betterschool.domain.errand.model.valobj.ErrandOrderEventVO;
import com.betterschool.domain.errand.model.valobj.ErrandPayResult;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.errand.model.valobj.ErrandTimeoutResult;
import com.betterschool.domain.errand.model.valobj.ErrandTypeVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跑腿订单服务：状态机流转（先付后审，ADR-0003），全部条件 UPDATE 防并发；
 * 每个写动作由仓储方法自身事务原子；跨域动作（资金冻结/解冻/入账、信用分扣减/加分）不在此执行——
 * 通过结果对象返回，由 case 层顺序编排，跨域一致性靠"幂等 + 重试"（domain 不依赖其他域）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrandOrderServiceImpl implements IErrandOrderService {

    private static final int PAY_TIMEOUT_MINUTES = 15;
    /** 锁单确认窗口：从**锁单时刻**起算（与 {@link #PAY_TIMEOUT_MINUTES} 的「从创建时刻起算」口径不同） */
    private static final int LOCK_TIMEOUT_MINUTES = 5;
    private static final int AUTO_COMPLETE_HOURS = 24;
    private static final int SCAN_BATCH_SIZE = 100;

    /** 事件类型字面量（与仓储写入侧同口径）：接单方取消——重试幂等判定只认「最新事件 = 本人的 TAKER_CANCELLED」 */
    private static final String EVENT_TAKER_CANCELLED = "TAKER_CANCELLED";

    /** 防占单：滑动窗口时长（小时）——非自然日，避免「23:59 放弃 3 次 + 00:01 放弃 3 次」跨零点刷新逃逸 */
    private static final int GRAB_ABUSE_WINDOW_HOURS = 24;
    /** 防占单：窗口内「主动放弃」达此次数 → 触发冷却（超时未确认不计——不可控，惩罚不公） */
    private static final int GRAB_ABUSE_THRESHOLD = 3;
    /** 防占单：冷却时长（小时）——轻惩罚，与「放弃是正常动作」的定位相称（信用分只记违约，不扣） */
    private static final int GRAB_COOLDOWN_HOURS = 2;
    /** 防占单拒绝文案里的到期时刻格式 */
    private static final DateTimeFormatter COOLDOWN_UNTIL_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final IErrandRepository errandRepository;
    private final IErrandGrabAbusePort errandGrabAbusePort;

    @Override
    public String createOrder(String userId, String title, String type, String description, String pickupLocation,
                            String deliveryLocation, BigDecimal reward,
                            List<String> images, LocalDateTime deadline) {
        ErrandTypeVO typeVO = ErrandTypeVO.from(type);
        ErrandOrderEntity orderEntity = ErrandOrderEntity.create(userId, title, typeVO, description,
                pickupLocation, deliveryLocation, reward, images, deadline);
        // 落库 + "15 分钟未支付"超时注册同属一个仓储原子用例（提交后即时注册到超时中心）
        errandRepository.createWithPayTimeout(orderEntity, PAY_TIMEOUT_MINUTES);
        return orderEntity.getOrderId();
    }

    @Override
    public String createDraft(String userId, String title, String type, String description, String pickupLocation,
                            String deliveryLocation, BigDecimal reward,
                            List<String> images, LocalDateTime deadline) {
        // 草稿为私有停靠内容：字段可缺省、不做初筛（提交时才做），落 DRAFT 且不注册支付超时
        ErrandOrderEntity orderEntity = ErrandOrderEntity.createDraft(userId, title, toTypeOrNull(type), description,
                pickupLocation, deliveryLocation, reward, deadline, images);
        errandRepository.saveDraft(orderEntity);
        return orderEntity.getOrderId();
    }

    @Override
    public void updateDraft(String orderId, String userId, String title, String type, String description, String pickupLocation,
                            String deliveryLocation, BigDecimal reward, List<String> images, LocalDateTime deadline) {
        ErrandOrderEntity orderEntity = getOwnedDraft(orderId, userId);
        orderEntity.applyDraftContent(title, toTypeOrNull(type), description, pickupLocation, deliveryLocation,
                reward, deadline, images);
        errandRepository.updateDraft(orderEntity);
    }

    @Override
    public ErrandOrderEntity getOwnedDraft(String orderId, String userId) {
        ErrandOrderEntity orderEntity = errandRepository.findById(orderId);
        if (orderEntity == null || orderEntity.getStatus() == ErrandStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "草稿不存在");
        }
        if (!orderEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的跑腿订单");
        }
        if (orderEntity.getStatus() != ErrandStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "该订单不在草稿态，请刷新后重试");
        }
        return orderEntity;
    }

    @Override
    public void submitDraft(String orderId, String userId) {
        // 必填完整性 + 信用分门槛 + 同步初筛由 case 层前置；此处只做状态守卫与迁移
        getOwnedDraft(orderId, userId);
        // 状态迁移与 15 分钟支付超时注册同属一个仓储原子用例（提交即起支付钟）
        errandRepository.submitDraftWithPayTimeout(orderId, userId, PAY_TIMEOUT_MINUTES);
    }

    @Override
    public void deleteDraft(String orderId, String userId) {
        ErrandOrderEntity orderEntity = errandRepository.findById(orderId);
        if (orderEntity == null || orderEntity.getStatus() == ErrandStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "草稿不存在");
        }
        if (!orderEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的跑腿订单");
        }
        if (orderEntity.getStatus() != ErrandStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可删除");
        }
        if (!errandRepository.softDeleteDraft(orderId, userId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
    }

    @Override
    public ErrandPayResult payOrder(String orderId, String userId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能支付自己发布的订单");
        }
        // 幂等判定：已支付（PUBLISHED 待接单）视为上次支付已提交成功（重试场景），跳过直接返回
        if (orderEntity.getStatus() == ErrandStatusVO.PUBLISHED) {
            return new ErrandPayResult(orderEntity.getTotalReward());
        }
        if (orderEntity.getStatus() != ErrandStatusVO.PENDING_PAYMENT) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或不可支付");
        }
        // 状态迁移跟着钱走：支付事务内转 PUBLISHED（冻结悬赏、即对外可见进入大厅），
        // bs-errand-paid outbox 消息只承担"支付成功"通知，不承载状态迁移；
        // deadline 已随单落库（payById WHERE deadline > NOW() 拒绝已过期单）
        errandRepository.payWithPaidMessage(orderId, userId);
        return new ErrandPayResult(orderEntity.getTotalReward());
    }

    @Override
    public ErrandFeedPageVO listOrders(String type, String status, String sort, String cursor, int page, int pageSize) {
        String typeName = toTypeName(type);
        String statusName = toStatusName(status);
        String sortType = ErrandFeedCursorVO.normalizeSort(sort);
        // 大厅推荐页 = 无类型筛选 + 只看 PUBLISHED（索引表无 type 列，索引行也只按 PUBLISHED 可见）：
        // 任一条件不满足就走旧直查，两路公开口径对齐。status 未传时控制器已默认 PUBLISHED，显式传空串
        // 则解释为「全部公开状态」——旧直查给回 PUBLISHED/LOCKED/CONFIRMED/COMPLETED 的更宽行集，
        // 不能因为 `status=` 被静默收窄成索引口径
        boolean hallVisible = typeName == null && ErrandStatusVO.PUBLISHED.name().equals(statusName);
        if (!hallVisible) {
            return legacyPage(typeName, statusName, sortType, page, pageSize);
        }
        // 游标解码在 try 之外：非法游标是调用方错误（PARAM_ERROR），不是索引不可用的降级信号
        ErrandFeedCursorVO cursorVO = cursor == null || cursor.isBlank()
                ? ErrandFeedCursorVO.firstPage(sortType)
                : ErrandFeedCursorVO.decode(cursor, sortType);
        try {
            return pageByFeedIndex(sortType, cursorVO, pageSize);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("跑腿 feed 索引查询失败，降级 DB 直查：sort={}", sortType, e);
        }
        return legacyPage(typeName, statusName, sortType, page, pageSize);
    }

    /**
     * 索引路径：errand_feed_index 按 (排序键, errand_order_id) 索引序扫描出 id（多探一行探下页），
     * 再 {@code listByIds} 批量回查组装、按扫描序重排。下文浏览量取索引热度快照（列表路径的唯一来源，
     * 详情仍走计数窄表精确值）。下一页游标 = 本页最后一行的排序键锚点，只在确认有下一页时发放；
     * 扫描为空 = 游标已越过末尾，返回空页（不发游标）
     */
    private ErrandFeedPageVO pageByFeedIndex(String sortType, ErrandFeedCursorVO cursorVO, int pageSize) {
        List<ErrandFeedIndexItemVO> rows = errandRepository.scanFeedIndex(sortType, cursorVO, pageSize + 1);
        if (rows.isEmpty()) {
            return new ErrandFeedPageVO(List.of(), null, true);
        }
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<String> orderIds = rows.stream().map(ErrandFeedIndexItemVO::getErrandOrderId).toList();
        Map<String, ErrandOrderEntity> byId = errandRepository.listByIds(orderIds).stream()
                .collect(Collectors.toMap(ErrandOrderEntity::getOrderId, Function.identity(), (a, b) -> a, HashMap::new));
        // 索引行缺订单行（存量异常，重建 job 修）只跳过不补位：游标锚在扫描序上，补位会引入重复
        List<ErrandOrderEntity> orders = new ArrayList<>(orderIds.size());
        for (ErrandFeedIndexItemVO row : rows) {
            ErrandOrderEntity orderEntity = byId.get(row.getErrandOrderId());
            if (orderEntity == null) {
                continue;
            }
            orderEntity.setViewCount(row.getViewCount());
            orders.add(orderEntity);
        }
        String nextCursor = hasMore
                ? ErrandFeedCursorVO.anchorOf(sortType, rows.get(rows.size() - 1)).encode()
                : null;
        return new ErrandFeedPageVO(orders, nextCursor, true);
    }

    /** 旧直查路径（类型筛选 / 非 PUBLISHED 口径 / 降级）：offset 分页，精确 COUNT 由调用方另取 */
    private ErrandFeedPageVO legacyPage(String typeName, String statusName, String sortType,
                                        int page, int pageSize) {
        return new ErrandFeedPageVO(errandRepository.pageList(typeName, statusName, sortType,
                (page - 1) * pageSize, pageSize), null, false);
    }

    @Override
    public long countOrders(String type, String status) {
        return errandRepository.count(toTypeName(type), toStatusName(status));
    }

    @Override
    public void rebuildFeedIndex() {
        errandRepository.rebuildFeedIndex();
    }

    @Override
    public List<String> listViewedOrderIds(String userId, List<String> orderIds) {
        return errandRepository.selectViewedOrderIds(userId, orderIds);
    }

    @Override
    public ErrandOrderEntity orderDetail(String orderId, String currentUserId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        // 软删单对外与作者均不可见；草稿单仅作者本人可见
        if (orderEntity.getStatus() == ErrandStatusVO.DELETED
                || (orderEntity.getStatus() == ErrandStatusVO.DRAFT
                && !orderEntity.isOwner(currentUserId))) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在");
        }
        // 浏览计数（UV 口径，market-feed-uv 票 02）：fire-and-forget，入队即返回（窗口过后一组一个事务
        // 落明细 + 加速列）。匿名不计入（仓储层拦下）；允许丢、不影响本次请求结果。
        // 详情页读到的仍是库里的当前值（跑腿此前无浏览量，从切码起按 UV 精确累计）
        errandRepository.recordOrderView(orderId, currentUserId);
        orderEntity.setViewCount(errandRepository.viewCountOf(orderId));
        return orderEntity;
    }

    @Override
    public void grabOrder(String orderId, String userId) {
        // 防占单前置（条件 UPDATE 之前）：冷却期内禁抢全量订单——冷却键是用户维度，天然覆盖「该单」
        requireNotGrabCooledDown(userId);
        ErrandOrderEntity orderEntity = getOrder(orderId);
        if (orderEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "不能抢自己发布的订单");
        }
        if (orderEntity.getStatus() != ErrandStatusVO.PUBLISHED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或不可抢");
        }
        // 信用分门槛由 case 层前置校验
        // 条件 UPDATE 保证先到先得：状态 + 无人持有 + 时限三重校验，并发下仅一人成功；
        // 5 分钟锁单超时注册与 PUBLISHED→LOCKED 同属一个仓储原子用例（复合 bizId 带新轮次），回滚即无幽灵注册
        if (!errandRepository.grabWithLockTimeout(orderId, userId, LOCK_TIMEOUT_MINUTES)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "手慢了，订单已被抢或已超时");
        }
    }

    @Override
    public void acceptLockedOrder(String orderId, String userId) {
        requireLockedTaker(orderId, userId);
        // 不改 lock_seq：离开 LOCKED 后陈旧超时任务天然打不中
        if (!errandRepository.acceptLockedById(orderId, userId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
    }

    @Override
    public void abandonLockedOrder(String orderId, String userId) {
        requireLockedTaker(orderId, userId);
        // member 取本轮锁单轮次（锁单期 lock_seq 不变，读在放弃之前）：同单重抢是不同轮次、各自计一次
        int lockSeq = errandRepository.lockSeqOf(orderId);
        // 放弃是设计内的正常动作：不扣信用分、不落 TAKER_CANCELLED 事件（扣分只认该事件）。
        // ⚠️ 此方法按仓内同形约定无显式事务（条件 UPDATE 即提交）——「主效应成功后」即「提交后」；
        // 若未来给 abandonLockedById 包上事务，下面的计数必须挪进提交后钩子（afterCommit），否则回滚会留脏计数
        if (!errandRepository.abandonLockedById(orderId, userId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        // 主效应（条件 UPDATE + 事件）成功后再计数：Redis 写失败 = 少计 = 宽恕
        recordGrabAbandon(userId, orderId, lockSeq);
    }

    /** 防占单写点：滑动窗口计一次主动放弃；达阈值置冷却 + 重置窗口 + WARN。任何 Redis 异常只记日志（少计=宽恕） */
    private void recordGrabAbandon(String userId, String orderId, int lockSeq) {
        try {
            LocalDateTime now = LocalDateTime.now();
            long windowCount = errandGrabAbusePort.recordAbandon(userId, orderId, lockSeq,
                    now.minusHours(GRAB_ABUSE_WINDOW_HOURS));
            if (windowCount < GRAB_ABUSE_THRESHOLD) {
                return;
            }
            LocalDateTime cooldownUntil = errandGrabAbusePort.startCooldown(userId,
                    Duration.ofHours(GRAB_COOLDOWN_HOURS));
            // 重置计数窗口：否则冷却结束（t+2h）时窗口内那 3 次仍在 ⇒ 立刻再触发 ⇒ 实际封禁 ≈22h
            errandGrabAbusePort.resetWindow(userId);
            log.warn("[ErrandGrabAbuse] 触发接单冷却 userId={}, windowCount={}, cooldownUntil={}",
                    userId, windowCount, cooldownUntil);
        } catch (Exception e) {
            log.warn("[ErrandGrabAbuse] 放弃计数写入失败，少计=宽恕 userId={}, orderId={}", userId, orderId, e);
        }
    }

    /** 防占单读点：冷却期内拒抢（403 权限类拒绝，刻意区别于信用分门槛的 500——500 会污染错误语义） */
    private void requireNotGrabCooledDown(String userId) {
        LocalDateTime cooldownUntil = errandGrabAbusePort.cooldownUntil(userId,
                LocalDateTime.now().minusHours(GRAB_ABUSE_WINDOW_HOURS),
                GRAB_ABUSE_THRESHOLD, Duration.ofHours(GRAB_COOLDOWN_HOURS));
        if (cooldownUntil != null) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "因近期多次放弃接单，您已被限制接单，请于 "
                            + cooldownUntil.format(COOLDOWN_UNTIL_FORMATTER) + " 后再试");
        }
    }

    /** 锁单动作共同守卫（确认接单 / 放弃）：订单存在 + 持单人本人 + 仍在 LOCKED */
    private void requireLockedTaker(String orderId, String userId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isTaker(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有持单人本人可以操作");
        }
        if (orderEntity.getStatus() != ErrandStatusVO.LOCKED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单不在锁单中，请刷新后重试");
        }
    }

    @Override
    public void pickupOrder(String orderId, String userId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        if (orderEntity.getStatus() != ErrandStatusVO.CONFIRMED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或未在已确认状态");
        }
        checkTaker(orderEntity, userId);
        if (!errandRepository.pickupById(orderId, userId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
    }

    @Override
    public void deliverOrder(String orderId, String userId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        if (orderEntity.getStatus() != ErrandStatusVO.PICKED_UP) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或未在已取货状态");
        }
        checkTaker(orderEntity, userId);
        errandRepository.deliverByIdOrThrow(orderId, userId);
    }

    @Override
    public ErrandConfirmResult confirmOrder(String orderId, String userId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        checkPublisher(orderEntity, userId);
        // 幂等判定：已完成视为上次确认已提交成功（重试场景），跳过直接返回（同一入账明细，流水 uk 幂等）
        if (orderEntity.getStatus() == ErrandStatusVO.COMPLETED) {
            return completionResult(orderEntity);
        }
        if (orderEntity.getStatus() != ErrandStatusVO.DELIVERED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或未送达");
        }
        errandRepository.confirmWithSearchSync(orderId, userId);
        return completionResult(orderEntity);
    }

    @Override
    public ErrandCancelResult cancelOrder(String orderId, String userId, String requestId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        // 请求级幂等（request id）：同订单上已存在「本人 + 接单取消 + 同编号」事件 = 同一次点击的重放，
        // 直接回放成功结果、业务零执行。该判定必须先于角色路由——重放可能落在「已重抢并再确认」的
        // 新一轮上，此时请求人是当前接单人，不挡住就会把新一轮误取消（事件链判定挡不住这窗口）
        if (requestId != null && errandRepository.hasTakerCancelEvent(orderId, userId, requestId)) {
            return new ErrandCancelResult(false, orderEntity.getPublisherId(), orderEntity.getTotalReward(),
                    true, userId);
        }
        if (orderEntity.isOwner(userId)) {
            return cancelByPublisher(orderEntity);
        }
        if (orderEntity.isTaker(userId)) {
            return cancelByTaker(orderEntity, requestId);
        }
        // 幂等判定（重试场景：接单人字段已被清空，订单行无法辨认请求人身份）：
        // 已取消（终态）→ 视为发布方取消成功；最新事件是本人 TAKER_CANCELLED 且订单回 PUBLISHED →
        // 视为本人取消成功（takerId 取事件操作人；重试触发的扣分入账由 credit 流水 uk_user_reason_ref 去重）
        if (orderEntity.getStatus() == ErrandStatusVO.CANCELLED) {
            return new ErrandCancelResult(false, orderEntity.getPublisherId(), orderEntity.getTotalReward(), false, null);
        }
        ErrandOrderEventVO latestEvent = errandRepository.latestEventOf(orderId);
        if (orderEntity.getStatus() == ErrandStatusVO.PUBLISHED
                && latestEvent != null
                && EVENT_TAKER_CANCELLED.equals(latestEvent.getEventType())
                && userId.equals(latestEvent.getActorId())) {
            return new ErrandCancelResult(false, orderEntity.getPublisherId(), orderEntity.getTotalReward(),
                    true, latestEvent.getActorId());
        }
        throw new BusinessException(ResponseCode.FORBIDDEN, "只有订单双方可以取消");
    }

    private ErrandCancelResult cancelByPublisher(ErrandOrderEntity orderEntity) {
        if (orderEntity.getStatus() != ErrandStatusVO.PENDING_PAYMENT
                && orderEntity.getStatus() != ErrandStatusVO.PUBLISHED
                && orderEntity.getStatus() != ErrandStatusVO.LOCKED
                && orderEntity.getStatus() != ErrandStatusVO.CONFIRMED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "已取货后不可取消，如有纠纷请发起仲裁");
        }
        // 待支付未冻结无需退款；已发布/锁单中/已确认需解冻退回
        boolean needRefund = orderEntity.getStatus() != ErrandStatusVO.PENDING_PAYMENT;
        errandRepository.cancelByPublisherWithSearchSync(orderEntity.getOrderId(), orderEntity.getPublisherId());
        return new ErrandCancelResult(needRefund, orderEntity.getPublisherId(), orderEntity.getTotalReward(),
                false, null);
    }

    private ErrandCancelResult cancelByTaker(ErrandOrderEntity orderEntity, String requestId) {
        if (orderEntity.getStatus() != ErrandStatusVO.CONFIRMED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅已确认未取货时允许接单方取消");
        }
        if (!errandRepository.cancelByTaker(orderEntity.getOrderId(), orderEntity.getTakerId(), requestId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        // 接单后取消扣信用分（credit 域），订单回已发布重新可抢
        return new ErrandCancelResult(false, orderEntity.getPublisherId(), orderEntity.getTotalReward(),
                true, orderEntity.getTakerId());
    }

    @Override
    public void disputeOrder(String orderId, String userId) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        if (orderEntity.getStatus() != ErrandStatusVO.CONFIRMED
                && orderEntity.getStatus() != ErrandStatusVO.PICKED_UP
                && orderEntity.getStatus() != ErrandStatusVO.DELIVERED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "当前状态不可发起仲裁");
        }
        if (!orderEntity.isOwner(userId) && !orderEntity.isTaker(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有订单双方可以发起仲裁");
        }
        String actorRole = orderEntity.isOwner(userId) ? "PUBLISHER" : "TAKER";
        errandRepository.disputeWithSearchSync(orderId, userId, actorRole);
    }

    @Override
    public ErrandArbitrateResult arbitrateOrder(String orderId, String operatorId, String result) {
        ErrandOrderEntity orderEntity = getOrder(orderId);
        // 幂等判定：已结案的终态视为本次仲裁已提交成功（重试场景），按终态返回对应结果
        if (orderEntity.getStatus() == ErrandStatusVO.CANCELLED) {
            return new ErrandArbitrateResult(orderEntity.getPublisherId(), orderEntity.getTakerId(),
                    orderEntity.getTotalReward(), "cancel");
        }
        if (orderEntity.getStatus() == ErrandStatusVO.COMPLETED) {
            return new ErrandArbitrateResult(orderEntity.getPublisherId(), orderEntity.getTakerId(),
                    orderEntity.getTotalReward(), "complete", completionIncomes(orderEntity));
        }
        if (orderEntity.getStatus() != ErrandStatusVO.DISPUTED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或未在仲裁中");
        }
        if ("cancel".equals(result)) {
            if (!errandRepository.arbitrateWithSearchSync(orderId, operatorId, true)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            return new ErrandArbitrateResult(orderEntity.getPublisherId(), orderEntity.getTakerId(),
                    orderEntity.getTotalReward(), "cancel");
        }
        if ("complete".equals(result)) {
            if (!errandRepository.arbitrateWithSearchSync(orderId, operatorId, false)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            // 判接单方 = 一笔完成结算，口径同确认完成
            return new ErrandArbitrateResult(orderEntity.getPublisherId(), orderEntity.getTakerId(),
                    orderEntity.getTotalReward(), "complete", completionIncomes(orderEntity));
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "仲裁结果不合法（cancel/complete）");
    }

    @Override
    public List<ErrandOrderEntity> listMyOrders(String role, String status, String userId, int page, int pageSize) {
        String statusName = toStatusName(status);
        if ("publish".equals(role)) {
            return errandRepository.listMyPublished(userId, statusName, (page - 1) * pageSize, pageSize);
        }
        if ("take".equals(role)) {
            return errandRepository.listMyTaken(userId, statusName, (page - 1) * pageSize, pageSize);
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "role 不合法（publish/take）");
    }

    @Override
    public List<ErrandOrderEntity> listUserPublishedOrders(String userId, int page, int pageSize) {
        return errandRepository.listPublicByPublisher(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countUserPublishedOrders(String userId) {
        return errandRepository.countPublicByPublisher(userId);
    }

    @Override
    public long countMyOrders(String role, String status, String userId) {
        String statusName = toStatusName(status);
        if ("publish".equals(role)) {
            return errandRepository.countMyPublished(userId, statusName);
        }
        if ("take".equals(role)) {
            return errandRepository.countMyTaken(userId, statusName);
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "role 不合法（publish/take）");
    }

    @Override
    public void handlePayTimeout(String orderId) {
        ErrandOrderEntity orderEntity = errandRepository.findById(orderId);
        if (orderEntity == null || orderEntity.getStatus() != ErrandStatusVO.PENDING_PAYMENT) {
            // 已支付/已取消：幂等忽略
            return;
        }
        errandRepository.cancelPendingPayment(orderId);
        log.info("[ErrandPayTimeout] 待支付订单超时取消 orderId={}", orderId);
    }

    @Override
    public ErrandLockTimeoutResult handleLockTimeout(String orderId, int lockSeq) {
        ErrandOrderEntity orderEntity = errandRepository.findById(orderId);
        if (orderEntity == null || orderEntity.getStatus() != ErrandStatusVO.LOCKED) {
            // 已确认 / 已放弃 / 已取消 / 不存在：过期回调，幂等忽略
            return null;
        }
        // 释放会清 taker_id，必须在清空前读出被释放的接单方（供 case 层发结果通知）
        String releasedTakerId = orderEntity.getTakerId();
        // 条件 UPDATE 带**轮次守卫**（lock_seq=?）：已放弃并重抢到新轮次时，陈旧任务的 lockSeq 打不中；
        // 单靠状态守卫挡不住（新一轮同样是 LOCKED）
        if (!errandRepository.releaseLockedByTimeout(orderId, lockSeq)) {
            return null;
        }
        log.info("[ErrandLockTimeout] 锁单超时释放回待接单 orderId={}, lockSeq={}, releasedTakerId={}",
                orderId, lockSeq, releasedTakerId);
        return new ErrandLockTimeoutResult(releasedTakerId);
    }

    @Override
    public List<String> listPayTimeoutOrderIds() {
        // 锚在"进入待支付"时刻（update_time）：直接发布=落库时刻，草稿提交=提交时刻——草稿可能先存很久再提交
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(PAY_TIMEOUT_MINUTES);
        return errandRepository.listPayTimeoutOrderIds(cutoff, SCAN_BATCH_SIZE);
    }

    @Override
    public List<String> listTimeoutOrderIds() {
        return errandRepository.listTimeoutOrderIds(SCAN_BATCH_SIZE);
    }

    @Override
    public List<String> listAutoCompleteOrderIds() {
        // DELIVERED 由送达条件 UPDATE 翻转而来，updated_at 即送达时间（24h 未确认自动完成）
        LocalDateTime cutoff = LocalDateTime.now().minusHours(AUTO_COMPLETE_HOURS);
        return errandRepository.listAutoCompleteOrderIds(cutoff, SCAN_BATCH_SIZE);
    }

    @Override
    public ErrandOrderEntity loadOrder(String orderId) {
        return errandRepository.findById(orderId);
    }

    @Override
    public ErrandTimeoutResult handleOrderTimeout(String orderId) {
        ErrandOrderEntity orderEntity = errandRepository.findById(orderId);
        if (orderEntity == null) {
            return null;
        }
        ErrandStatusVO status = orderEntity.getStatus();
        if (status != ErrandStatusVO.PUBLISHED
                && status != ErrandStatusVO.LOCKED
                && status != ErrandStatusVO.CONFIRMED
                && status != ErrandStatusVO.PICKED_UP) {
            // 已完成/已取消/仲裁中/已送达/待支付：过期消息或不受超时影响，幂等忽略
            return null;
        }
        // 扣分口径＝「已承诺后违约」：锁单期还没开工（抢 ≠ 开工），与「放弃不扣分」同口径
        boolean committed = status == ErrandStatusVO.CONFIRMED || status == ErrandStatusVO.PICKED_UP;
        // 条件 UPDATE（状态 + deadline 双校验）防并发重复处理
        if (!errandRepository.markTimeoutWithSearchSync(orderId)) {
            return null;
        }
        log.info("[ErrandTimeout] 订单超时取消 orderId={}, wasStatus={}", orderId, status);
        return new ErrandTimeoutResult(orderEntity.getPublisherId(), orderEntity.getTakerId(),
                orderEntity.getTotalReward(), committed);
    }

    @Override
    public ErrandAutoCompleteResult handleAutoComplete(String orderId) {
        ErrandOrderEntity orderEntity = errandRepository.findById(orderId);
        if (orderEntity == null || orderEntity.getStatus() != ErrandStatusVO.DELIVERED) {
            // 已确认/已仲裁/其他：幂等忽略
            return null;
        }
        if (!errandRepository.autoCompleteWithSearchSync(orderId)) {
            return null;
        }
        log.info("[ErrandAutoComplete] 送达超时自动完成 orderId={}", orderId);
        // 与发布方确认同一结算口径
        return new ErrandAutoCompleteResult(orderEntity.getPublisherId(), orderEntity.getTotalReward(),
                completionIncomes(orderEntity));
    }

    /**
     * 完成结算结果（发布方确认 / 24h 自动完成共用）：解冻总额 + 入账明细。
     * 接单方得悬赏（总额）
     */
    private ErrandConfirmResult completionResult(ErrandOrderEntity orderEntity) {
        return new ErrandConfirmResult(orderEntity.getPublisherId(), orderEntity.getTotalReward(),
                completionIncomes(orderEntity));
    }

    /** 入账明细：接单方单条（悬赏） */
    private List<ErrandIncomeVO> completionIncomes(ErrandOrderEntity orderEntity) {
        return List.of(new ErrandIncomeVO(orderEntity.getTakerId(), orderEntity.getReward()));
    }

    private ErrandOrderEntity getOrder(String orderId) {
        ErrandOrderEntity orderEntity = errandRepository.findById(orderId);
        if (orderEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在");
        }
        return orderEntity;
    }

    private void checkPublisher(ErrandOrderEntity orderEntity, String userId) {
        if (!orderEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有下单方可以操作");
        }
    }

    private void checkTaker(ErrandOrderEntity orderEntity, String userId) {
        if (!orderEntity.isTaker(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有接单方可以操作");
        }
    }

    private String toTypeName(String type) {
        return type == null || type.isBlank() ? null : ErrandTypeVO.from(type).name();
    }

    /** 草稿可缺省类型：非空才校验枚举（提交时必填由 case 层守卫） */
    private ErrandTypeVO toTypeOrNull(String type) {
        return type == null || type.isBlank() ? null : ErrandTypeVO.from(type);
    }

    private String toStatusName(String status) {
        return status == null || status.isBlank() ? null : ErrandStatusVO.from(status).name();
    }
}
