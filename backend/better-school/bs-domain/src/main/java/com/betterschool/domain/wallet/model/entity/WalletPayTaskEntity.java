package com.betterschool.domain.wallet.model.entity;

import com.betterschool.domain.wallet.model.valobj.WalletPayTaskStateEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 支付回调任务（{@code wallet_pay_task}，ADR-0017「域内最终一致任务表」）。
 * <p>
 * 不是 MQ 消息：行的职责是「这笔支付对应的商户回调还没确认完成」，由扫描 job
 * {@code productPayTaskScan} 进程内重放，不经过 broker。行由钱包支付事务同事务写入
 * （与支付单 PAID、买家 {@code PRODUCT_PAY} / 平台 {@code PLATFORM_IN} 双边流水、余额扣减一起提交/回滚），
 * 因此「行在 ⇒ 钱已过账、支付单已 PAID」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletPayTaskEntity {

    /** 物理自增 id（扫描抢占 / 死信按 id 条件更新） */
    private Long id;
    /** 商品订单业务 ID（uk_order_id：同一订单只落一条任务） */
    private String orderId;
    private WalletPayTaskStateEnum state;
    /** 重试次数（扫描抢占时递增） */
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
