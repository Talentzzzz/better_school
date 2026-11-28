package com.betterschool.domain.wallet.service;

import com.betterschool.domain.wallet.adapter.port.IPaymentNotifyPort;
import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.valobj.PaymentPayResult;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 支付渠道服务（钱包域扮演「微信支付」）：预下单、按支付单号支付、回调商户、关闭支付单。
 * <p>
 * 资金动作（支付单置 PAID + 冻结流水 + 余额条件扣减 + 回调任务行）在 {@link IWalletRepository#pay}
 * 的单仓储事务内完成；事务提交后才调用 {@link IPaymentNotifyPort} 通知商户（跨域动作不并事务，
 * ADR-0016）。回调失败不撤销支付，任务行留 PENDING 由 {@code productPayTaskScan} 重放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletPaymentServiceImpl implements IWalletPaymentService {

    private final IWalletRepository walletRepository;
    private final IPaymentNotifyPort paymentNotifyPort;

    @Override
    public PaymentOrderEntity createPaymentOrder(String userId, String bizOrderId, BigDecimal amount, String subject) {
        return walletRepository.createPaymentOrder(userId, bizOrderId, amount, subject);
    }

    @Override
    public PaymentOrderEntity getPaymentOrder(String payNo, String userId) {
        PaymentOrderEntity paymentOrderEntity = walletRepository.getPaymentOrderByPayNo(payNo);
        if (paymentOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "支付单不存在");
        }
        if (!paymentOrderEntity.getUserId().equals(userId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能支付自己的订单");
        }
        return paymentOrderEntity;
    }

    @Override
    public PaymentPayResult pay(String payNo, String userId) {
        // 钱包事务：支付单置 PAID + 冻结资金 + 回调任务行（任一步失败整体回滚）
        PaymentPayResult paymentPayResult = walletRepository.pay(payNo, userId);
        try {
            boolean accepted = paymentNotifyPort.notifyPaid(paymentPayResult.getPayNo(),
                    paymentPayResult.getBizOrderId(), paymentPayResult.getPayerId());
            if (!accepted) {
                // 订单已关闭/不存在：永久无需再通知；已冻结的钱由资金孤儿扫描退买家
                log.warn("[支付回调] 商户拒绝或订单已关闭，停止重试 payNo={}, bizOrderId={}",
                        paymentPayResult.getPayNo(), paymentPayResult.getBizOrderId());
            }
            walletRepository.markPayTaskDone(paymentPayResult.getBizOrderId());
        } catch (Exception e) {
            // 瞬时故障：支付已成功、任务行留 PENDING，由 productPayTaskScan 退避重放
            log.warn("[支付回调] 通知商户失败，留待扫描重放 payNo={}, bizOrderId={}",
                    paymentPayResult.getPayNo(), paymentPayResult.getBizOrderId(), e);
        }
        return paymentPayResult;
    }

    @Override
    public void closePaymentOrder(String bizOrderId) {
        walletRepository.closePaymentOrder(bizOrderId);
    }

    @Override
    public PaymentOrderEntity getPaymentOrderByBizOrderId(String bizOrderId) {
        return walletRepository.getPaymentOrderByBizOrderId(bizOrderId);
    }
}
