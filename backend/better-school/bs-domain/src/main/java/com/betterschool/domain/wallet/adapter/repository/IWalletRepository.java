package com.betterschool.domain.wallet.adapter.repository;

import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;

import java.math.BigDecimal;
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

    /** 冻结（带流水类型，uk_order_type 按域区分）：单个原子用例 */
    void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type);

    /** 冻结（带业务轮次）：同一订单同类型可有多轮合法冻结（撤单重发再支付等业务轮次），uk 按 biz_round 区分：单个原子用例 */
    void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type, int bizRound);

    /** 解冻退回（+流水 UNFROZEN）：单个原子用例 */
    void unfreeze(String userId, BigDecimal amount, String orderId);

    /** 解冻退回（带流水类型）：单个原子用例 */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type);

    /** 解冻退回（带业务轮次，与冻结轮次对齐）：单个原子用例 */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type, int bizRound);

    /** 接单方入账（+流水 INCOME）：单个原子用例 */
    void income(String userId, BigDecimal amount, String orderId);

    /** 接收方入账（带流水类型）：单个原子用例 */
    void income(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type);

    List<WalletDetailEntity> listDetails(String userId, int offset, int limit);

    long countDetails(String userId);

    /**
     * 孤儿扫描输入（ADR-0016 两段式）：存在冻结流水、但同订单同轮次没有对应解冻流水、
     * 且冻结时间早于阈值的行（按 create_time 升序）。单仓储自查（冻结与解冻都在本表比对），
     * 不跨域；流水类型成对传入——同表不同 type，各域扫描线只认自己的类型对；
     * 「是否该解冻」的业务判据（订单是否终态）
     * 由扫描用例查订单域裁决
     */
    List<WalletDetailEntity> listOrphanFrozen(WalletDetailTypeVO frozenType, WalletDetailTypeVO unfrozenType,
                                              int thresholdMinutes, int limit);
}
