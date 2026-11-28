package com.betterschool.usecase.payment;

import com.betterschool.domain.wallet.adapter.port.IPaymentNotifyPort;
import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.entity.WalletPayTaskEntity;
import com.betterschool.domain.wallet.model.valobj.PaymentOrderStateEnum;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.domain.wallet.service.IWalletPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付渠道用例（模拟微信支付）：收银台按支付单号支付、支付单查询、支付回调重放。
 * <ul>
 *   <li>收银台支付：按支付单号出账（买家余额 → 平台代收账户）+ 回调商户（钱包支付服务内完成）；</li>
 *   <li>回调收敛：支付成功但商户未确认（进程崩溃 / 回调失败）由 {@code productPayTaskScan} 重放。</li>
 * </ul>
 *
 * <p>本类只做渠道侧编排，不注入任何其他 case：预下单 / 关支付单由商户订单用例（{@code IdleCase}）
 * 直接编排钱包域完成；钱包回调商户仍走 {@link IPaymentNotifyPort}（实现方
 * {@code PaymentNotifyHandler} 只依赖 idle 域服务）。
 *
 * <p>资金动作在钱包域单仓储事务内（支付单 PAID + 买家出账 / 平台入账双边流水 + 买家余额条件扣减 + 任务行）；
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

    private final IWalletPaymentService walletPaymentService;
    private final IWalletAccountService walletAccountService;
    private final IPaymentNotifyPort paymentNotifyPort;

    /** 收银台支付：按支付单号支付（钱包出账 + 进程内回调商户） */
    public void pay(String payNo, String userId) {
        walletPaymentService.pay(payNo, userId);
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
            // 订单已取消 / 不存在：永久无需再通知；已收进平台的货款由 productFundOrphanScan 退买家
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
}
