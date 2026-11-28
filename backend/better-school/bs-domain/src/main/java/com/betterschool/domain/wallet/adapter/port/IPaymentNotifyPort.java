package com.betterschool.domain.wallet.adapter.port;

/**
 * 支付结果回调端口（模拟微信支付的 notify_url，进程内实现）。
 * <p>
 * 钱包支付事务提交后调用：把「支付单已支付」通知商户（idle 域）翻转订单。
 * 这是跨域回调，不在钱包事务内；实现方必须幂等（重复通知零副作用）。
 */
public interface IPaymentNotifyPort {

    /**
     * 通知商户支付成功。
     *
     * @return true  = 已受理（含订单已支付的幂等重放），回调完成、任务行可置 DONE
     *         false = 订单已关闭/不存在，永久无需再通知（冻结款由资金孤儿扫描收敛退款）
     * @throws RuntimeException 瞬时故障，调用方保留任务行，由 {@code productPayTaskScan} 退避重放
     */
    boolean notifyPaid(String payNo, String bizOrderId, String payerId);
}
