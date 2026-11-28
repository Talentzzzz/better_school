package com.betterschool.usecase.payment;

import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusEnum;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.wallet.adapter.port.IPaymentNotifyPort;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 支付成功回调的商户侧实现（模拟微信 notify_url 的进程内形态）：
 * 钱包支付事务提交后调用，把「待支付 → 已支付」翻转交给 idle 域。
 * <p>
 * 必须幂等：已支付 / 已交付 / 已完成直接返回 true；订单已取消返回 false（永久拒绝）；
 * 其余瞬时异常上抛，让钱包保留回调任务行由 {@code productPayTaskScan} 重放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentNotifyHandler implements IPaymentNotifyPort {

    private final IIdleService idleService;

    @Override
    public boolean notifyPaid(String payNo, String bizOrderId, String payerId) {
        ProductOrderEntity productOrderEntity = idleService.getOrderEntity(bizOrderId);
        if (productOrderEntity == null) {
            log.error("[支付回调] 支付单对应订单不存在 payNo={}, bizOrderId={}", payNo, bizOrderId);
            return false;
        }
        if (payerId != null && !payerId.equals(productOrderEntity.getBuyerId())) {
            log.error("[支付回调] 付款人与订单买家不一致 payNo={}, bizOrderId={}, payerId={}, buyerId={}",
                    payNo, bizOrderId, payerId, productOrderEntity.getBuyerId());
            return false;
        }
        ProductOrderStatusEnum status = productOrderEntity.getStatus();
        if (status == ProductOrderStatusEnum.CANCELLED) {
            // 支付与取消竞态：支付赢了取消前的校验，但订单已终态；冻结款由 productFundOrphanScan 退买家
            log.warn("[支付回调] 订单已取消，拒绝回调 payNo={}, bizOrderId={}", payNo, bizOrderId);
            return false;
        }
        if (status == ProductOrderStatusEnum.PAID || status == ProductOrderStatusEnum.DELIVERED
                || status == ProductOrderStatusEnum.COMPLETED) {
            return true; // 幂等：重复通知 / 重放
        }
        if (status != ProductOrderStatusEnum.PENDING_PAYMENT) {
            log.error("[支付回调] 订单状态不可支付 payNo={}, bizOrderId={}, status={}", payNo, bizOrderId, status);
            return false;
        }
        try {
            idleService.confirmPayOrder(bizOrderId, productOrderEntity.getBuyerId());
            return true;
        } catch (BusinessException e) {
            // 翻转期间可能被并发取消：终态按幂等口径回读，不把永久拒绝当瞬时故障反复重试
            ProductOrderEntity latest = idleService.getOrderEntity(bizOrderId);
            if (latest == null || latest.getStatus() == ProductOrderStatusEnum.CANCELLED) {
                log.warn("[支付回调] 翻转时订单已取消 payNo={}, bizOrderId={}", payNo, bizOrderId);
                return false;
            }
            if (latest.getStatus() == ProductOrderStatusEnum.PAID
                    || latest.getStatus() == ProductOrderStatusEnum.DELIVERED
                    || latest.getStatus() == ProductOrderStatusEnum.COMPLETED) {
                return true;
            }
            throw e;
        }
    }
}
