package com.betterschool.domain.wallet.service;

import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;

import java.math.BigDecimal;
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

    /** 冻结悬赏：流水先行（uk 幂等），余额不足抛 400 */
    void freeze(String userId, BigDecimal amount, String orderId);

    /** 冻结（带流水类型，uk_order_type 幂等按域区分：跑腿 FROZEN / 商品 PRODUCT_FROZEN） */
    void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type);

    /** 冻结（带业务轮次）：同一订单同类型多轮合法冻结（撤单重发再支付等业务轮次），uk 按 biz_round 区分 */
    void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type, int bizRound);

    /** 解冻退回（幂等） */
    void unfreeze(String userId, BigDecimal amount, String orderId);

    /** 解冻退回（带流水类型） */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type);

    /** 解冻退回（带业务轮次，与冻结轮次对齐） */
    void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type, int bizRound);

    /** 接单方入账（幂等） */
    void income(String userId, BigDecimal amount, String orderId);

    /** 接收方入账（带流水类型） */
    void income(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type);

    /** 查余额（钱包不存在时懒建，自愈"注册成功但建钱包失败"） */
    VirtualWalletEntity getBalance(String userId);

    List<WalletDetailEntity> listDetails(String userId, int page, int pageSize);

    long countDetails(String userId);
}
