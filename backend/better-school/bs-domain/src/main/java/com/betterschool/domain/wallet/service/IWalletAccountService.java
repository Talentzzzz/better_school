package com.betterschool.domain.wallet.service;

import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.entity.WalletPayTaskEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeEnum;

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

    /** mock 充值（v1 虚拟币，无真实支付） */
    void recharge(String userId, BigDecimal amount);

    /** 冻结金额：流水先行（uk 幂等），余额不足抛 400 */
    void freeze(String userId, BigDecimal amount, String orderId);

    /** 冻结（带流水类型，uk_order_type 幂等按域区分）；商品支付的冻结由支付渠道的支付事务完成 */
    void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO);

    /** 解冻退回（幂等） */
    void unfreeze(String userId, BigDecimal amount, String orderId);

    /** 解冻退回（带流水类型） */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO);

    /** 解冻退回（带业务轮次，与冻结轮次对齐） */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO, int bizRound);

    /** 接单方入账（幂等） */
    void income(String userId, BigDecimal amount, String orderId);

    /** 接收方入账（带流水类型） */
    void income(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO);

    /**
     * 支付回调任务置 DONE：未完成才生效（重复标记幂等）。同步回调成功或扫描重放成功后调用；
     * 失败时任务留 PENDING，由 {@code productPayTaskScan} 下轮重放（回调幂等）
     */
    void markPayTaskDone(String orderId);

    // ---------- 资金补偿扫描（资金孤儿扫描 / 支付回调扫描的取数口） ----------

    /** 冻结孤儿候选：有 frozenType 流水、同订单同轮次无 unfrozenType 流水且已超阈值的流水行 */
    List<WalletDetailEntity> listOrphanFrozen(WalletDetailTypeEnum frozenType, WalletDetailTypeEnum unfrozenType,
                                              int thresholdMinutes, int limit);

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
