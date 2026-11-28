package com.betterschool.domain.wallet.service;

import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.entity.WalletPayTaskEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 虚拟钱包服务：资金操作（流水先行 + 余额）各自域内事务原子；
 * 跨域调用方（case 层）顺序调用即可，重试靠流水 uk_order_type 幂等
 */
public interface IWalletAccountService {

    /** 注册初始化：无则创建并赠送初始币（INSERT IGNORE，幂等） */
    void ensureWallet(String userId);

    /**
     * 懒创建钱包行并给定初始余额（INSERT IGNORE，幂等）：注册路径用注册赠送额，
     * 平台代收账户用 0（平台不能凭空多出余额，否则对账口径就错）。
     */
    void ensureWallet(String userId, BigDecimal initialBalance);

    /** mock 充值（v1 虚拟币，无真实支付） */
    void recharge(String userId, BigDecimal amount);

    /**
     * 确认收货结算：平台出账 + 卖家入账，单仓储事务、双边流水幂等；平台余额不足整笔回滚。
     * <p>
     * 卖家 {@code PRODUCT_INCOME} 流水是幂等锚（重复确认收货不会二次入账）；平台腿走
     * {@code PLATFORM_OUT}，与退款共用同一类型——同一订单只有一个终态，唯一键因此保证平台
     * 对同一订单只出账一次。
     */
    void settleProductOrder(String orderId, String sellerId, BigDecimal amount);

    /**
     * 取消 / 支付超时退款：平台出账 + 买家入账，单仓储事务、双边流水幂等；平台余额不足整笔回滚。
     * <p>
     * 买家 {@code PRODUCT_REFUND} 流水是幂等锚（重复退款不会二次入账）；平台腿同样是
     * {@code PLATFORM_OUT}，方向由订单状态与配对行判定，不为退款另开平台类型。
     */
    void refundProductOrder(String orderId, String buyerId, BigDecimal amount);

    /**
     * 支付回调任务置 DONE：未完成才生效（重复标记幂等）。同步回调成功或扫描重放成功后调用；
     * 失败时任务留 PENDING，由 {@code productPayTaskScan} 下轮重放（回调幂等）
     */
    void markPayTaskDone(String orderId);

    // ---------- 资金补偿扫描（资金孤儿扫描 / 支付回调扫描的取数口） ----------

    /**
     * 平台侧自查：平台有 {@code PLATFORM_IN}、同订单同轮次无 {@code PLATFORM_OUT} 且已超阈值的流水行，
     * 即「平台已收但未结算 / 未退款」——按订单终态补结算或补退款由扫描用例裁决（宁可长款）。
     */
    List<WalletDetailEntity> listOrphanPlatformIn(int thresholdMinutes, int limit);

    /** 到点待重放的支付回调任务（state=PENDING 且到退避时点） */
    List<WalletPayTaskEntity> listDuePayTasks(int limit);

    /** 抢占支付回调任务（递增 retry_count 并写退避时点）；false = 被其他实例抢先 / 状态已漂移 */
    boolean claimPayTask(Long id, LocalDateTime nextRetryAt);

    /** 支付回调任务置死信（重试耗尽） */
    void markPayTaskDead(Long id);

    /** 查余额（钱包不存在时懒建，自愈"注册成功但建钱包失败"） */
    VirtualWalletEntity getBalance(String userId);

    List<WalletDetailEntity> listDetails(String userId, int page, int pageSize);

    long countDetails(String userId);
}
