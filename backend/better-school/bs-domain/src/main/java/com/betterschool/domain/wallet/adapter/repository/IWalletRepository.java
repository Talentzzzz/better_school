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

    /**
     * 懒创建钱包行并给定初始余额（INSERT IGNORE，撞 {@code uk_user} 幂等）。
     * <p>
     * 为什么把余额做成参数：注册路径要的是注册赠送额，而平台代收账户必须从 0 起账
     * （否则平台凭空多出余额，对账口径当场就错）。让调用方决定起始余额，仓储不再假设
     * 「所有账户都从同一笔赠送额开账」。
     */
    void ensureWallet(String userId, BigDecimal initialBalance);

    VirtualWalletEntity getByUserId(String userId);

    /** mock 充值（+余额 +流水 RECHARGE）：单个原子用例 */
    void recharge(String userId, BigDecimal amount);

    /**
     * 确认收货结算（单仓储事务）：平台出账 + 卖家入账，双边流水 + 双边余额同生共死。
     * <p>
     * 幂等锚是业务侧那一行——卖家的 {@link WalletDetailTypeEnum#PRODUCT_INCOME}；它的
     * {@code INSERT IGNORE} 影响 0 行即整笔视为已结算过，直接返回、不动任何余额。
     * 平台腿的 {@link WalletDetailTypeEnum#PLATFORM_OUT} 行紧随其后同事务写入。
     * <p>
     * 结算与退款共用 {@code PLATFORM_OUT}：同一订单只会终态一次（{@code COMPLETED} 结算 /
     * {@code CANCELLED} 退款），{@code uk_order_type(order_id, type, biz_round, user_id)} 因此保证
     * 「平台对同一订单只出账一次」，不需要为退款另开一个平台专用类型。
     * <p>
     * 平台余额守卫 {@code balance >= amount}：不足视为不变量破坏（平台付了没收过的钱），
     * 记 ERROR 并抛异常整笔回滚，绝不静默跳过、绝不把平台余额扣成负数。
     */
    void settleProductOrder(String orderId, String sellerId, BigDecimal amount);

    /**
     * 取消 / 支付超时退款（单仓储事务）：平台出账 + 买家入账，形状与
     * {@link #settleProductOrder(String, String, BigDecimal)} 完全一致，只换幂等锚与收款方——
     * 锚是买家的 {@link WalletDetailTypeEnum#PRODUCT_REFUND} 行，0 行即已退过款、直接返回。
     * <p>
     * 平台腿同样是 {@code PLATFORM_OUT}：与结算共用同一类型的理由见结算方法（订单只有一个终态，
     * 唯一键天然保证平台只出账一次）。平台余额不足同样记 ERROR + 抛异常整笔回滚。
     */
    void refundProductOrder(String orderId, String buyerId, BigDecimal amount);

    List<WalletDetailEntity> listDetails(String userId, int offset, int limit);

    long countDetails(String userId);

    /**
     * 孤儿扫描输入（平台侧线索）：平台有 {@link WalletDetailTypeEnum#PLATFORM_IN}、同订单同轮次却
     * 没有配对的 {@link WalletDetailTypeEnum#PLATFORM_OUT}、且入账时间早于阈值的流水行。
     * <p>
     * 平台有入账无出账 = 平台已收钱但没结算也没退款，钱悬在平台账上；这是「流程在过账后被截断」
     * 的唯一线索（既有 {@code PRODUCT_PAY} 又有 {@code PLATFORM_IN} 却走不到终态时，买家那侧已经
     * 扣过钱，不能再按买家流水判断为「未支付」）。单表内自查，不跨域；「该结算还是该退款」由扫描
     * 用例查订单域裁决。
     */
    List<WalletDetailEntity> listOrphanPlatformIn(int thresholdMinutes, int limit);

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
     * 按支付单号支付（单仓储事务）：「买家出账 + 平台入账」一笔过账——支付单 WAIT_PAY→PAID
     * + 买家 {@link WalletDetailTypeEnum#PRODUCT_PAY} 出账流水（uk 幂等，整笔的幂等锚）
     * + 平台 {@link WalletDetailTypeEnum#PLATFORM_IN} 入账流水 + 买家可用余额条件扣减
     * + 平台余额累加 + 回调任务行 PENDING。任一步失败整体回滚，支付单仍 WAIT_PAY。
     * <p>
     * 已 PAID 的重复支付、或买家出账流水已存在时返回 alreadyPaid=true，不再动资金。
     * 商品链路不再写 {@code frozen_amount}：钱在支付时就真的离开买家账户，历史冻结额
     * 只在买家出账的余额守卫（{@code balance - frozen_amount >= amount}）里作为减项保留。
     */
    PaymentPayResult pay(String payNo, String userId);

    /** 关闭支付单：仅 WAIT_PAY → CLOSED；已支付 / 已关闭是空操作 */
    void closePaymentOrder(String bizOrderId);
}
