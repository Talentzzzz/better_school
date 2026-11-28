package com.betterschool.domain.wallet.adapter.repository;

import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.entity.WalletPayTaskEntity;
import com.betterschool.domain.wallet.model.valobj.PaymentPayResult;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 虚拟钱包仓储：资金操作全部"流水先行（uk_order_type 幂等）+ 余额条件 UPDATE"，
 * 每个资金动作由仓储方法自身用事务（REQUIRED）收成一个原子用例。
 */
public interface IWalletRepository {

    /** 注册初始化：无则创建并赠送初始币（INSERT IGNORE，幂等） */
    void ensureWallet(String userId);

    VirtualWalletEntity getByUserId(String userId);

    /** mock 充值（+余额 +流水 RECHARGE）：单个原子用例 */
    void recharge(String userId, BigDecimal amount);

    /** 冻结金额：可用余额（balance - frozen）不足抛 400（+流水 FROZEN）：单个原子用例 */
    void freeze(String userId, BigDecimal amount, String orderId);

    /**
     * 冻结（带流水类型）：流水 + 余额条件 UPDATE 一个原子用例。
     * 商品支付的冻结不走本方法，由 {@link #pay(String, String)} 在支付事务内与支付单状态、
     * 回调任务行一起完成（见下方支付单小节）
     */
    void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO);

    /** 解冻退回（+流水 UNFROZEN）：单个原子用例 */
    void unfreeze(String userId, BigDecimal amount, String orderId);

    /** 解冻退回（带流水类型）：单个原子用例 */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO);

    /** 解冻退回（带业务轮次，与冻结轮次对齐）：单个原子用例 */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO, int bizRound);

    /** 接单方入账（+流水 INCOME）：单个原子用例 */
    void income(String userId, BigDecimal amount, String orderId);

    /** 接收方入账（带流水类型）：单个原子用例 */
    void income(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO);

    List<WalletDetailEntity> listDetails(String userId, int offset, int limit);

    long countDetails(String userId);

    /**
     * 孤儿扫描输入（ADR-0016 两段式）：存在冻结流水、但同订单同轮次没有对应解冻流水、
     * 且冻结时间早于阈值的行（按 create_time 升序）。单仓储自查（冻结与解冻都在本表比对），
     * 不跨域；流水类型成对传入——同表不同 type，各域扫描线只认自己的类型对；
     * 「是否该解冻」的业务判据（订单是否终态）
     * 由扫描用例查订单域裁决
     */
    List<WalletDetailEntity> listOrphanFrozen(WalletDetailTypeEnum frozenType, WalletDetailTypeEnum unfrozenType,
                                              int thresholdMinutes, int limit);

    // ---------- 支付回调任务（wallet_pay_task，域内最终一致任务，ADR-0017） ----------

    /** 到期待收敛的支付任务（state=0 且到点），按 id 升序 */
    List<WalletPayTaskEntity> listDuePayTasks(int limit);

    /** 抢占式认领：递增重试次数并写退避时点；false = 被其他实例抢先 / 状态漂移 */
    boolean claimPayTask(Long id, LocalDateTime nextRetryAt);

    /** 置 DONE：未完成才生效（同步支付与扫描重放共用，幂等） */
    void markPayTaskDone(String orderId);

    /** 置 DEAD（重试耗尽） */
    void markPayTaskDead(Long id);

    // ---------- 支付单（payment_order：模拟微信支付的统一下单 / 支付 / 关闭） ----------

    /** 预下单：按 bizOrderId 幂等，已存在原样返回；pay_no 由应用层发号随 insert 写入 */
    PaymentOrderEntity createPaymentOrder(String userId, String bizOrderId, BigDecimal amount, String subject);

    PaymentOrderEntity getPaymentOrderByPayNo(String payNo);

    PaymentOrderEntity getPaymentOrderByBizOrderId(String bizOrderId);

    /**
     * 按支付单号支付（单仓储事务）：支付单 WAIT_PAY→PAID + PRODUCT_FROZEN 流水（uk 幂等）
     * + 可用余额条件扣减 + 回调任务行 PENDING。任一步失败整体回滚，支付单仍 WAIT_PAY。
     * 已 PAID 的重复支付返回 alreadyPaid=true，不再动资金。
     */
    PaymentPayResult pay(String payNo, String userId);

    /** 关闭支付单：仅 WAIT_PAY → CLOSED；已支付 / 已关闭是空操作 */
    void closePaymentOrder(String bizOrderId);
}
