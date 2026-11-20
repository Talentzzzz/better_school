package com.betterschool.usecase.errand;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.chat.adapter.port.IWsPushPort;
import com.betterschool.domain.chat.model.valobj.ChatWsFrame;
import com.betterschool.domain.chat.model.valobj.ChatWsFrameType;
import com.betterschool.domain.credit.model.valobj.CreditReasonTypeVO;
import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandArbitrateResult;
import com.betterschool.domain.errand.model.valobj.ErrandCancelResult;
import com.betterschool.domain.errand.model.valobj.ErrandConfirmResult;
import com.betterschool.domain.errand.model.valobj.ErrandOrderPushEvent;
import com.betterschool.domain.errand.model.valobj.ErrandFeedPageVO;
import com.betterschool.domain.errand.model.valobj.ErrandIncomeVO;
import com.betterschool.domain.errand.model.valobj.ErrandLockTimeoutResult;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.errand.service.IErrandOrderService;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跑腿用例编排：跨域组合顺序调用各域服务（每个域各自事务原子），
 * 一致性靠"幂等 + 重试"——各域方法内状态机判定/流水唯一键保证重试安全，本层不做事务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrandOrderCase {

    /** 悬赏下限（与直接发布的 @DecimalMin(10) 同口径，提交草稿时补齐校验） */
    private static final BigDecimal MIN_REWARD = new BigDecimal("10");

    private final IErrandOrderService errandOrderService;
    private final ICreditAccountService creditAccountService;
    private final IWalletAccountService walletAccountService;
    private final INotificationService notificationService;
    private final IWsPushPort wsPushPort;

    public String createOrder(String userId, String title, String type, String description, String pickupLocation,
                            String deliveryLocation, BigDecimal reward,
                            List<String> images, LocalDateTime deadline) {
        creditAccountService.ensureOperable(userId);
        checkImages(images);
        return errandOrderService.createOrder(userId, title, type, description, pickupLocation, deliveryLocation,
                reward, images, deadline);
    }

    /**
     * 支付（资金收口）：冻结悬赏与「待支付 → 已发布」翻转（含 outbox + feed 索引行）
     * 已由 IErrandFundPort 并入一个物理事务（见 ErrandOrderServiceImpl.payOrder），
     * 本层不再拆两步调用——「发布成功但钱没冻」的中间态由该事务消灭
     */
    public void payOrder(String orderId, String userId) {
        errandOrderService.payOrder(orderId, userId);
    }

    /** 保存草稿：私有停靠内容——不做同步初筛、不做信用分门槛（提交时才校验），字段可缺省 */
    public String createDraft(String userId, String title, String type, String description, String pickupLocation,
                            String deliveryLocation, BigDecimal reward, List<String> images, LocalDateTime deadline) {
        checkImages(images);
        return errandOrderService.createDraft(userId, title, type, description, pickupLocation, deliveryLocation,
                reward, images, deadline);
    }

    /** 编辑草稿：仅 DRAFT 可编辑，全量覆盖内容字段（作者与状态位不可改、不产生状态迁移） */
    public void updateDraft(String orderId, String userId, String title, String type, String description, String pickupLocation,
                            String deliveryLocation, BigDecimal reward, List<String> images, LocalDateTime deadline) {
        checkImages(images);
        errandOrderService.updateDraft(orderId, userId, title, type, description, pickupLocation, deliveryLocation,
                reward, images, deadline);
    }

    /**
     * 提交草稿：与直接发布同门槛（必填完整性 + 信用分准入门槛）；
     * 通过后转 PENDING_PAYMENT 且支付超时随状态迁移同事务注册
     */
    public void submitDraft(String orderId, String userId) {
        ErrandOrderEntity draftEntity = errandOrderService.getOwnedDraft(orderId, userId);
        creditAccountService.ensureOperable(userId);
        validateDraftComplete(draftEntity);
        errandOrderService.submitDraft(orderId, userId);
    }

    /** 删除草稿：软删 DRAFT → DELETED（仅作者、仅 DRAFT） */
    public void deleteDraft(String orderId, String userId) {
        errandOrderService.deleteDraft(orderId, userId);
    }

    /** 大厅列表（market-feed-uv 票 04）：索引路径出 cursor / total 降口径，降级与筛选路径走旧直查 */
    public ErrandFeedPageVO listOrders(String type, String status, String sort, String cursor, int page, int pageSize) {
        return errandOrderService.listOrders(type, status, sort, cursor, page, pageSize);
    }

    public long countOrders(String type, String status) {
        return errandOrderService.countOrders(type, status);
    }

    /** 大厅已读灰显（market-feed-uv 票 05）：页内订单批量回查当前用户浏览过的 id，未登录返回空集 */
    public List<String> listViewedOrderIds(String userId, List<String> orderIds) {
        return errandOrderService.listViewedOrderIds(userId, orderIds);
    }

    /** 作者主页「跑腿」tab：某用户发布的进行中跑腿单（PUBLISHED/LOCKED/CONFIRMED，只列发布不含接单） */
    public List<ErrandOrderEntity> listUserOrders(String userId, int page, int pageSize) {
        return errandOrderService.listUserPublishedOrders(userId, page, pageSize);
    }

    public long countUserOrders(String userId) {
        return errandOrderService.countUserPublishedOrders(userId);
    }

    public ErrandOrderEntity orderDetail(String orderId, String currentUserId) {
        return errandOrderService.orderDetail(orderId, currentUserId);
    }

    /**
     * 订单状态推送编排（bs-order-push 消费落地）：定位订单双方后逐个投递 ORDER 帧
     * （payload={orderId, status}）。草稿态（DELETED/DRAFT）无「双方」概念，不推；
     * 推送是加速路径：不做事务、不做重试，不在线/失败由 IWsPushPort 静默放弃——
     * 漏推由详情页下次拉取与通知中心兜底
     */
    public void pushStatus(ErrandOrderPushEvent event) {
        ErrandOrderEntity orderEntity = errandOrderService.loadOrder(event.getOrderId());
        if (orderEntity == null) {
            return;
        }
        deliverTo(orderEntity.getPublisherId(), event);
        if (orderEntity.getTakerId() != null) {
            deliverTo(orderEntity.getTakerId(), event);
        }
    }

    /** 向单个收件人投递一帧 ORDER 通知（订单双方由 pushStatus 逐个调用） */
    private void deliverTo(String userId, ErrandOrderPushEvent event) {
        wsPushPort.deliver(userId, ChatWsFrame.builder()
                .type(ChatWsFrameType.ORDER)
                .payload(Map.of(
                        "orderId", event.getOrderId(),
                        "status", event.getStatus()))
                .ts(System.currentTimeMillis())
                .build());
    }

    public void grabOrder(String orderId, String userId) {
        creditAccountService.ensureOperable(userId);
        errandOrderService.grabOrder(orderId, userId);
    }

    /** 确认接单（锁单→已确认）：抢到手后核对信息再点，仅持单人 */
    public void acceptLockedOrder(String orderId, String userId) {
        errandOrderService.acceptLockedOrder(orderId, userId);
    }

    /** 放弃（锁单→已发布）：设计内的正常动作，不扣信用分；主动放弃的滑动计数与冷却由域层写点处理（票 05） */
    public void abandonLockedOrder(String orderId, String userId) {
        errandOrderService.abandonLockedOrder(orderId, userId);
    }

    public void pickupOrder(String orderId, String userId) {
        errandOrderService.pickupOrder(orderId, userId);
    }

    public void deliverOrder(String orderId, String userId) {
        errandOrderService.deliverOrder(orderId, userId);
    }

    public void confirmOrder(String orderId, String userId) {
        ErrandConfirmResult confirmResult = errandOrderService.confirmOrder(orderId, userId);
        walletAccountService.unfreeze(confirmResult.getPublisherId(), confirmResult.getReward(), orderId);
        // 入账：接单方得悬赏（流水 uk 幂等）
        for (ErrandIncomeVO income : confirmResult.getIncomes()) {
            walletAccountService.income(income.getUserId(), income.getAmount(), orderId);
        }
    }

    /**
     * 取消（资金收口）：发布方解冻退款与取消翻转已由 IErrandFundPort 并入一个物理事务
     * （见 ErrandOrderServiceImpl.cancelByPublisher），本层不再拆两步调用——
     * 「订单已取消但悬赏悬置冻结态」的中间态由该事务消灭。
     * 接单方取消不涉资金（悬赏继续托管），仅扣信用分（流水 uk 幂等）。
     */
    public void cancelOrder(String orderId, String userId, String requestId) {
        // requestId 可空（请求级幂等键）：同一次点击的重放在 domain 层已被回放拦截，不会走到跨域动作
        ErrandCancelResult cancelResult = errandOrderService.cancelOrder(orderId, userId, requestId);
        if (cancelResult.isTakerCanceled()) {
            creditAccountService.applyChange(cancelResult.getTakerId(), CreditReasonTypeVO.ERRAND_TAKER_CANCEL, orderId);
        }
    }

    public void disputeOrder(String orderId, String userId) {
        errandOrderService.disputeOrder(orderId, userId);
    }

    public void arbitrateOrder(String orderId, String operatorId, String result) {
        ErrandArbitrateResult arbitrateResult = errandOrderService.arbitrateOrder(orderId, operatorId, result);
        walletAccountService.unfreeze(arbitrateResult.getPublisherId(), arbitrateResult.getReward(), orderId);
        // 判接单方 = 一笔完成结算（接单方得悬赏）；判取消不结算
        for (ErrandIncomeVO income : arbitrateResult.getIncomes()) {
            walletAccountService.income(income.getUserId(), income.getAmount(), orderId);
        }
    }

    public List<ErrandOrderEntity> listMyOrders(String role, String status, String userId, int page, int pageSize) {
        return errandOrderService.listMyOrders(role, status, userId, page, pageSize);
    }

    public long countMyOrders(String role, String status, String userId) {
        return errandOrderService.countMyOrders(role, status, userId);
    }

    public void handlePayTimeout(String orderId) {
        errandOrderService.handlePayTimeout(orderId);
    }

    /**
     * 锁单超时释放编排（超时中心回调）：释放回 PUBLISHED 后给**被释放的接单方**发一条结果通知，
     * dedupKey 带 lockSeq——多轮释放各发各的（同一轮重复回调被唯一键吃掉）。
     * 通知是尽力而为：释放（轮次守卫条件 UPDATE）才是主效应，通知失败只记日志——回调重投时释放已命中 0 行、
     * 通知也不会重试，故不让通知异常把一次成功的释放报成失败（换取零死信噪音）。
     */
    public void handleLockTimeout(String orderId, int lockSeq) {
        ErrandLockTimeoutResult lockTimeoutResult = errandOrderService.handleLockTimeout(orderId, lockSeq);
        if (lockTimeoutResult == null) {
            return; // 幂等：陈旧轮次 / 已确认 / 已放弃 / 已取消，零副作用
        }
        try {
            notificationService.publish(NotificationEventMessage.builder()
                    .notificationId(UUID.randomUUID().toString())
                    .userId(lockTimeoutResult.getTakerId())
                    .scope(NotificationScopeVO.MARKET_ERRAND.name())
                    .type(NotificationTypeVO.ORDER_EVENT.name())
                    .title("订单动态")
                    .content("您锁定的跑腿订单已超时释放，订单回到待接单，其他同学可重新接单")
                    .refType("ERRAND")
                    .refId(orderId)
                    .dedupKey(NotificationTypeVO.ORDER_EVENT.name() + ":ERRAND_LOCK_TIMEOUT:" + orderId + ":" + lockSeq)
                    .build());
        } catch (Exception e) {
            log.warn("[ErrandOrderCase] 锁单超时释放通知发送失败 orderId={} lockSeq={}", orderId, lockSeq, e);
        }
    }

    /**
     * 支付成功消费方（bs-errand-paid）编排：给作者发"支付成功、订单已可见"通知。
     * 通知失败向上抛由 broker 重试（dedup_key 幂等，重试不重复落），
     * 主流程（订单状态/资金）不在本方法内，不受影响
     */
    public void notifyPaid(String orderId) {
        ErrandOrderEntity orderEntity = errandOrderService.loadOrder(orderId);
        if (orderEntity == null) {
            return;
        }
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(orderEntity.getPublisherId())
                .scope(NotificationScopeVO.MARKET_ERRAND.name())
                .type(NotificationTypeVO.ORDER_EVENT.name())
                .title("订单动态")
                .content("您的跑腿订单已支付成功，现已对外可见")
                .refType("ERRAND")
                .refId(orderId)
                .dedupKey(NotificationTypeVO.ORDER_EVENT.name() + ":ERRAND_PAID:" + orderId)
                .build());
    }

    /**
     * 订单超时取消编排（超时中心回调，资金收口）：取消翻转 + 发布方解冻退款
     * （开工场景另含接单方信用分扣减）已由 IErrandFundPort 并入一个物理事务
     * （见 ErrandOrderServiceImpl.handleOrderTimeout），本层不再拆两步调用——
     * 「订单已取消但悬赏悬置冻结态」「退了钱分没扣」的中间态由该事务消灭。
     * 终态短路语义保持现状：重投一个已处理的回调，读到终态即安全返回；
     * 早到回调仍按失败重投（资金动作随翻转失败整体回滚，零残留）。
     */
    public void handleOrderTimeout(String orderId) {
        errandOrderService.handleOrderTimeout(orderId);
    }

    /**
     * 送达 24h 自动完成编排（超时中心回调，资金收口）：发布方解冻退款 + 接单方悬赏收入
     * 与「已送达 → 已完成」翻转已由 IErrandFundPort 并入一个物理事务
     * （见 ErrandOrderServiceImpl.handleAutoComplete），本层不再拆两步调用——
     * 「订单已完成但接单方悬赏收入永久不结算」的中间态由该事务消灭。
     * 终态短路语义保持现状：重投一个已处理的回调，读到终态即安全返回（资金已随终态完成过）。
     */
    public void handleAutoComplete(String orderId) {
        errandOrderService.handleAutoComplete(orderId);
    }

    /** 配图上限（直接发布与草稿共用同一口径，封顶 6 张） */
    private void checkImages(List<String> images) {
        if (images != null && images.size() > Constants.ERRAND_MAX_IMAGES) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "配图最多 6 张");
        }
    }

    /** 提交门槛：必填 = 直接发布的必填项（标题/类型/描述/取送地点/悬赏≥10/截止时间） */
    private void validateDraftComplete(ErrandOrderEntity draftEntity) {
        if (isBlank(draftEntity.getTitle())
                || draftEntity.getType() == null
                || isBlank(draftEntity.getDescription())
                || isBlank(draftEntity.getPickupLocation())
                || isBlank(draftEntity.getDeliveryLocation())
                || draftEntity.getReward() == null
                || draftEntity.getReward().compareTo(MIN_REWARD) < 0
                || draftEntity.getDeadline() == null) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR,
                    "草稿内容不完整，请补全标题、类型、描述、取送地点、悬赏（不低于 10）与截止时间后再提交");
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
