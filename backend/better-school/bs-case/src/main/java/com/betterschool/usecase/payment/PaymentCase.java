package com.betterschool.usecase.payment;

import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusEnum;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.wallet.adapter.port.IPaymentNotifyPort;
import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.entity.WalletPayTaskEntity;
import com.betterschool.domain.wallet.model.valobj.PaymentOrderStateEnum;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.domain.wallet.service.IWalletPaymentService;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付用例编排（模拟微信支付）：
 * <ul>
 *   <li>预下单：下单时 / 订单详情按 bizOrderId 幂等确保支付单，返回支付单号；</li>
 *   <li>收银台支付：按支付单号扣冻 + 回调商户（钱包支付服务内完成）；</li>
 *   <li>回调收敛：支付成功但商户未确认（进程崩溃 / 回调失败）由 {@code productPayTaskScan} 重放。</li>
 * </ul>
 *
 * <p>资金动作在钱包域单仓储事务内（支付单 PAID + 冻结流水 + 余额条件扣减 + 任务行）；
 * 本层不做事务，只编排跨域顺序（ADR-0016）。回调端口实现方保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCase {

    /** 支付回调重试上限（与发帖返利领取任务同口径）：耗尽置死信，人工重置回 PENDING 可自愈 */
    private static final int PAY_TASK_MAX_RETRY = 5;

    /** 支付回调重试退避（分钟）：1/2/4/8/16，封顶最后一档 */
    private static final List<Duration> PAY_TASK_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
            Duration.ofMinutes(8), Duration.ofMinutes(16));

    /** 收银台标题长度上限（payment_order.subject） */
    private static final int SUBJECT_MAX_LENGTH = 100;

    private final IIdleService idleService;
    private final IWalletPaymentService walletPaymentService;
    private final IWalletAccountService walletAccountService;
    private final IPaymentNotifyPort paymentNotifyPort;

    /**
     * 确保商户订单的支付单存在并返回支付单号（统一下单，按 bizOrderId 幂等）。
     * 仅买家、仅待支付订单可预下单；其余状态返回 null。
     */
    public String ensurePayNo(ProductOrderEntity productOrderEntity, String userId) {
        if (productOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在");
        }
        if (!productOrderEntity.isBuyer(userId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能支付自己的订单");
        }
        if (productOrderEntity.getStatus() != ProductOrderStatusEnum.PENDING_PAYMENT) {
            return null;
        }
        PaymentOrderEntity paymentOrderEntity = walletPaymentService.createPaymentOrder(userId,
                productOrderEntity.getOrderId(), productOrderEntity.totalAmount(), subjectOf(productOrderEntity));
        return paymentOrderEntity == null ? null : paymentOrderEntity.getPayNo();
    }

    /** 收银台支付：按支付单号支付（钱包扣冻 + 进程内回调商户） */
    public void pay(String payNo, String userId) {
        walletPaymentService.pay(payNo, userId);
    }

    /**
     * 内部便捷入口：按商户订单号支付（测试 / 内部调用）。先做买家与状态守卫，
     * 再按支付单号走同一支付链路；已支付及之后的订单幂等返回。
     */
    public void payOrder(String orderId, String buyerId) {
        ProductOrderEntity productOrderEntity = idleService.preparePayOrder(orderId, buyerId);
        if (productOrderEntity.getStatus() != ProductOrderStatusEnum.PENDING_PAYMENT) {
            return; // 幂等：上次支付已提交 / 订单已流转
        }
        String payNo = ensurePayNo(productOrderEntity, buyerId);
        if (payNo == null) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "支付单未生成，请稍后重试");
        }
        pay(payNo, buyerId);
    }

    /** 关闭支付单（订单取消 / 超时）：仅 WAIT_PAY 生效，已支付是空操作 */
    public void closePaymentOrder(String bizOrderId) {
        walletPaymentService.closePaymentOrder(bizOrderId);
    }

    /** 收银台查询（服务层校验支付单归属） */
    public PaymentOrderEntity getPaymentOrder(String payNo, String userId) {
        return walletPaymentService.getPaymentOrder(payNo, userId);
    }

    /**
     * 支付回调收敛扫描（{@code productPayTaskScan}，cron 0/30）：到点的 PENDING 任务重新通知商户，
     * 失败按 1/2/4/8/16 分钟退避，重试耗尽置死信。逐行隔离，一行失败不中断本轮批次。
     */
    public int replayDueNotifyTasks(int limit) {
        int completed = 0;
        for (WalletPayTaskEntity task : walletAccountService.listDuePayTasks(limit)) {
            // 抢占：条件更新递增 retry_count 并写退避时点；被其他实例抢先 / 状态漂移则跳过
            if (!walletAccountService.claimPayTask(task.getId(), nextPayTaskBackoffAt(task.getRetryCount()))) {
                continue;
            }
            try {
                if (replayNotifyTask(task)) {
                    completed++;
                }
            } catch (Exception e) {
                if (task.getRetryCount() + 1 >= PAY_TASK_MAX_RETRY) {
                    walletAccountService.markPayTaskDead(task.getId());
                    log.error("[支付回调] 重试耗尽置死信，需人工介入 orderId={}", task.getOrderId(), e);
                } else {
                    log.warn("[支付回调] 重放失败，留待下轮 orderId={} attempt={}", task.getOrderId(),
                            task.getRetryCount() + 1, e);
                }
            }
        }
        return completed;
    }

    /**
     * 重放一笔支付回调：任务行只可能由「支付事务已提交」产生，对应支付单必为 PAID。
     * 支付单缺失 / 状态漂移属于数据不一致，置死信人工核查。
     */
    private boolean replayNotifyTask(WalletPayTaskEntity task) {
        PaymentOrderEntity paymentOrderEntity = walletPaymentService.getPaymentOrderByBizOrderId(task.getOrderId());
        if (paymentOrderEntity == null) {
            walletAccountService.markPayTaskDead(task.getId());
            log.error("[支付回调] 任务对应支付单不存在，置死信需人工核查 orderId={}", task.getOrderId());
            return false;
        }
        if (paymentOrderEntity.getState() != PaymentOrderStateEnum.PAID) {
            walletAccountService.markPayTaskDead(task.getId());
            log.error("[支付回调] 任务对应支付单非已支付状态，置死信需人工核查 orderId={}, state={}",
                    task.getOrderId(), paymentOrderEntity.getState());
            return false;
        }
        boolean accepted = paymentNotifyPort.notifyPaid(paymentOrderEntity.getPayNo(),
                paymentOrderEntity.getBizOrderId(), paymentOrderEntity.getUserId());
        if (!accepted) {
            // 订单已取消 / 不存在：永久无需再通知；冻结款由 productFundOrphanScan 退买家
            log.warn("[支付回调] 商户拒绝或订单已关闭，停止重试 orderId={}", task.getOrderId());
        }
        walletAccountService.markPayTaskDone(task.getOrderId());
        return true;
    }

    /** 第 retryCount+1 次尝试的退避时点（封顶最后一档 16 分钟） */
    private LocalDateTime nextPayTaskBackoffAt(Integer retryCount) {
        int index = Math.min(retryCount == null ? 0 : retryCount, PAY_TASK_BACKOFF.size() - 1);
        return LocalDateTime.now().plus(PAY_TASK_BACKOFF.get(Math.max(index, 0)));
    }

    /** 收银台展示标题：商品标题 + 件数，超长截断 */
    private String subjectOf(ProductOrderEntity productOrderEntity) {
        String title = productOrderEntity.getProductTitle() == null ? "商品订单" : productOrderEntity.getProductTitle();
        int quantity = productOrderEntity.getQuantity() == null ? 1 : productOrderEntity.getQuantity();
        String subject = quantity > 1 ? title + " × " + quantity : title;
        return subject.length() > SUBJECT_MAX_LENGTH ? subject.substring(0, SUBJECT_MAX_LENGTH) : subject;
    }
}
