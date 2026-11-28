package com.betterschool.infrastructure.dao.wallet.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付回调任务行（{@code wallet_pay_task}，域内最终一致 outbox，ADR-0017 / ADR-0018）。
 * 由钱包支付事务（{@code WalletRepository.pay}）同事务写入，不是 MQ outbox；回调失败留下的行
 * 由 {@code productPayTaskScan} 进程内重放。
 */
@Data
public class WalletPayTaskPO {

    private Long id;
    private String orderId;
    /** 0 待回调 / 1 已完成 / 2 死信 */
    private Integer state;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
