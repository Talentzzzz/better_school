package com.betterschool.infrastructure.adapter.repository.wallet;

import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.entity.WalletPayTaskEntity;
import com.betterschool.domain.wallet.model.valobj.PaymentOrderStateEnum;
import com.betterschool.domain.wallet.model.valobj.PaymentPayResult;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeEnum;
import com.betterschool.domain.wallet.model.valobj.WalletPayTaskStateEnum;
import com.betterschool.infrastructure.dao.wallet.IPaymentOrderDao;
import com.betterschool.infrastructure.dao.wallet.IWalletDao;
import com.betterschool.infrastructure.dao.wallet.IWalletDetailDao;
import com.betterschool.infrastructure.dao.wallet.IWalletPayTaskDao;
import com.betterschool.infrastructure.dao.wallet.po.PaymentOrderPO;
import com.betterschool.infrastructure.dao.wallet.po.VirtualWallet;
import com.betterschool.infrastructure.dao.wallet.po.WalletDetail;
import com.betterschool.infrastructure.dao.wallet.po.WalletPayTaskPO;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.id.IIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 虚拟钱包仓储：资金动作"流水先行（uk_order_type 幂等）+ 余额条件 UPDATE"，每个动作由方法自身
 * 用事务（REQUIRED）收成一个原子用例——余额不足抛 PARAM_ERROR 时流水随之回滚；平台出账余额不足
 * 记 ERROR 后抛 BUSINESS_ERROR，同样整笔回滚（平台余额绝不允许为负）
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WalletRepository implements IWalletRepository {

    /** 注册赠送额：只用于注册路径的建钱包口径（平台代收账户从 0 起账，不读本常量） */
    private static final BigDecimal REGISTER_GIFT_BALANCE = new BigDecimal("1000");

    /** 支付回调任务首次可扫延迟：给请求线程的同步回调留窗口，避免扫描与刚提交的请求并发重放（幂等但白跑） */
    private static final long PAY_TASK_GRACE_SECONDS = 60;

    private final IWalletDao walletDao;
    /** 钱包流水（wallet_detail）按表拆分的 DAO */
    private final IWalletDetailDao walletDetailDao;
    /** 支付回调任务（wallet_pay_task 单表，域内最终一致任务，ADR-0017） */
    private final IWalletPayTaskDao walletPayTaskDao;
    /** 支付单（payment_order 单表：模拟微信支付的统一下单/支付/关闭） */
    private final IPaymentOrderDao paymentOrderDao;
    /** 业务 ID 发号器（支付单号 payNo 随 insert 一次写入） */
    private final IIdGenerator idGenerator;

    /** 资金动作都是「流水先行 + 余额条件 UPDATE」的原子用例：仓储方法内用事务（REQUIRED）收口 */
    private final TransactionTemplate transactionTemplate;

    @Override
    public void ensureWallet(String userId) {
        // 注册路径口径不变：初始余额 = 注册赠送额；其它账户（平台代收）走带余额的重载传自己的起始值
        ensureWallet(userId, REGISTER_GIFT_BALANCE);
    }

    @Override
    public void ensureWallet(String userId, BigDecimal initialBalance) {
        VirtualWallet virtualWallet = new VirtualWallet();
        // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；INSERT IGNORE 撞 uk_user 时本行未落库，发号作废无害
        virtualWallet.setWalletId(UUID.randomUUID().toString());
        virtualWallet.setUserId(userId);
        virtualWallet.setBalance(initialBalance);
        virtualWallet.setFrozenAmount(BigDecimal.ZERO);
        walletDao.insertIgnore(virtualWallet);
    }

    /**
     * 平台代收账户懒创建（余额 0，撞 uk_user 幂等）。
     * <p>
     * 为什么必须在本类每个资金事务的开头调用：{@code insertIgnoreDetail} 是
     * {@code INSERT ... SELECT ... FROM virtual_wallet WHERE user_id = ?}，平台钱包行不存在时
     * 平台那一腿流水会静默插入 0 行（不是报错），幂等锚与配对行就都失效了。
     */
    private void ensurePlatformWallet() {
        ensureWallet(Constants.PLATFORM_USER_ID, BigDecimal.ZERO);
    }

    @Override
    public VirtualWalletEntity getByUserId(String userId) {
        return toEntity(walletDao.selectByUserId(userId));
    }

    /** 流水 + 余额一个原子用例 */
    @Override
    public void recharge(String userId, BigDecimal amount) {
        WalletDetail walletDetail = new WalletDetail();
        walletDetail.setUserId(userId);
        walletDetail.setType(WalletDetailTypeEnum.RECHARGE.name());
        walletDetail.setAmount(amount);
        walletDetail.setBizRound(0);
        transactionTemplate.execute(status -> {
            // RECHARGE 无 order_id，不受 uk 约束（mock 充值不要求幂等）
            if (walletDetailDao.insertIgnoreDetail(walletDetail) == 0) {
                return null;
            }
            walletDao.updateIncreaseBalance(userId, amount);
            return null;
        });
    }

    /** 支付回调任务行：首次可扫延后 {@link #PAY_TASK_GRACE_SECONDS} 秒，给请求线程的同步回调留窗口 */
    private WalletPayTaskPO newPayTask(String orderId) {
        LocalDateTime now = LocalDateTime.now();
        WalletPayTaskPO walletPayTaskPO = new WalletPayTaskPO();
        walletPayTaskPO.setOrderId(orderId);
        walletPayTaskPO.setState(WalletPayTaskStateEnum.PENDING.getCode());
        walletPayTaskPO.setRetryCount(0);
        walletPayTaskPO.setNextRetryAt(now.plusSeconds(PAY_TASK_GRACE_SECONDS));
        walletPayTaskPO.setCreateTime(now);
        walletPayTaskPO.setUpdateTime(now);
        return walletPayTaskPO;
    }

    /**
     * 确认收货结算：平台出账 + 卖家入账，一个钱包事务。
     * <p>
     * 事务内顺序固定为「双边流水 → 双边余额」：幂等锚（卖家 {@code PRODUCT_INCOME}）先行，
     * 它返回 0 就整笔返回——流水表是事实源，锚没进去说明这笔早结算过，此时动任何余额都会重复记账。
     * 平台腿 {@code PLATFORM_OUT} 与退款共用同一类型：同一订单只有一个终态，唯一键
     * {@code uk_order_type(order_id, type, biz_round, user_id)} 因此保证平台对同一订单只出账一次。
     */
    @Override
    public void settleProductOrder(String orderId, String sellerId, BigDecimal amount) {
        transactionTemplate.execute(status -> {
            // 平台钱包行必须先存在，否则下面 PLATFORM_OUT 的 INSERT ... SELECT 会插 0 行
            ensurePlatformWallet();
            // 幂等锚 = 业务侧那一行（卖家结算行）：0 行即整笔已结算过，余额一律不碰
            WalletDetail sellerDetail = new WalletDetail();
            sellerDetail.setUserId(sellerId);
            sellerDetail.setType(WalletDetailTypeEnum.PRODUCT_INCOME.name());
            sellerDetail.setAmount(amount);
            sellerDetail.setOrderId(orderId);
            sellerDetail.setBizRound(0);
            if (walletDetailDao.insertIgnoreDetail(sellerDetail) == 0) {
                return null;
            }
            insertPlatformDetail(WalletDetailTypeEnum.PLATFORM_OUT, orderId, amount);
            // 平台余额守卫：不足视为不变量破坏（平台付了没收过的钱）→ ERROR + 抛异常，整笔回滚
            if (walletDao.updateDecreaseBalance(Constants.PLATFORM_USER_ID, amount) == 0) {
                VirtualWallet virtualWallet = walletDao.selectByUserId(Constants.PLATFORM_USER_ID);
                log.error("平台代收余额不足，结算整笔回滚：orderId={} sellerId={} amount={} platformBalance={}",
                        orderId, sellerId, amount, virtualWallet == null ? null : virtualWallet.getBalance());
                throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "平台代收余额不足，结算失败");
            }
            walletDao.updateIncreaseBalance(sellerId, amount);
            return null;
        });
    }

    /**
     * 取消 / 支付超时退款：平台出账 + 买家入账，形状与 {@link #settleProductOrder} 一致，
     * 只换幂等锚（买家 {@code PRODUCT_REFUND}）与收款方；平台腿同样是 {@code PLATFORM_OUT}
     * （订单只有一个终态，两个方向共用一个平台出账类型，不为退款另开类型）。
     */
    @Override
    public void refundProductOrder(String orderId, String buyerId, BigDecimal amount) {
        transactionTemplate.execute(status -> {
            ensurePlatformWallet();
            // 幂等锚 = 业务侧那一行（买家退款行）：0 行即整笔已退过款，余额一律不碰
            WalletDetail buyerDetail = new WalletDetail();
            buyerDetail.setUserId(buyerId);
            buyerDetail.setType(WalletDetailTypeEnum.PRODUCT_REFUND.name());
            buyerDetail.setAmount(amount);
            buyerDetail.setOrderId(orderId);
            buyerDetail.setBizRound(0);
            if (walletDetailDao.insertIgnoreDetail(buyerDetail) == 0) {
                return null;
            }
            insertPlatformDetail(WalletDetailTypeEnum.PLATFORM_OUT, orderId, amount);
            // 平台余额守卫：不足 → ERROR + 抛异常，整笔回滚（绝不让平台余额为负）
            if (walletDao.updateDecreaseBalance(Constants.PLATFORM_USER_ID, amount) == 0) {
                VirtualWallet virtualWallet = walletDao.selectByUserId(Constants.PLATFORM_USER_ID);
                log.error("平台代收余额不足，退款整笔回滚：orderId={} buyerId={} amount={} platformBalance={}",
                        orderId, buyerId, amount, virtualWallet == null ? null : virtualWallet.getBalance());
                throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "平台代收余额不足，退款失败");
            }
            walletDao.updateIncreaseBalance(buyerId, amount);
            return null;
        });
    }

    /**
     * 平台侧配对流水：必须紧跟在业务侧幂等锚之后、与它同事务写入。
     * <p>
     * 写不进来说明该订单平台早已记过账（唯一键冲突）——此时两边账已经不一致，不能装作「已处理」
     * 继续动余额，否则同一订单会被平台收 / 付第二次。这里把唯一键当成硬守卫：记 ERROR 并整笔回滚，
     * 让「平台对同一订单只收一次、只付一次」真正由数据库约束守住，而不是靠调用顺序的善意。
     */
    private void insertPlatformDetail(WalletDetailTypeEnum platformType, String orderId, BigDecimal amount) {
        WalletDetail walletDetail = new WalletDetail();
        walletDetail.setUserId(Constants.PLATFORM_USER_ID);
        walletDetail.setType(platformType.name());
        walletDetail.setAmount(amount);
        walletDetail.setOrderId(orderId);
        walletDetail.setBizRound(0);
        if (walletDetailDao.insertIgnoreDetail(walletDetail) == 0) {
            log.error("平台流水已存在，资金状态不一致，整笔回滚：type={} orderId={} amount={}", platformType, orderId, amount);
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "平台流水已存在，资金状态不一致");
        }
    }

    @Override
    public List<WalletDetailEntity> listDetails(String userId, int offset, int limit) {
        return walletDetailDao.selectDetails(userId, offset, limit).stream().map(this::toDetailEntity).toList();
    }

    @Override
    public long countDetails(String userId) {
        return walletDetailDao.countDetails(userId);
    }

    private VirtualWalletEntity toEntity(VirtualWallet virtualWallet) {
        if (virtualWallet == null) {
            return null;
        }
        VirtualWalletEntity virtualWalletEntity = new VirtualWalletEntity();
        virtualWalletEntity.setWalletId(virtualWallet.getWalletId());
        virtualWalletEntity.setUserId(virtualWallet.getUserId());
        virtualWalletEntity.setBalance(virtualWallet.getBalance());
        virtualWalletEntity.setFrozenAmount(virtualWallet.getFrozenAmount());
        virtualWalletEntity.setCreatedAt(virtualWallet.getCreateTime());
        return virtualWalletEntity;
    }

    private WalletDetailEntity toDetailEntity(WalletDetail walletDetail) {
        WalletDetailEntity walletDetailEntity = new WalletDetailEntity();
        walletDetailEntity.setId(walletDetail.getId());
        walletDetailEntity.setWalletId(walletDetail.getWalletId());
        walletDetailEntity.setUserId(walletDetail.getUserId());
        walletDetailEntity.setType(WalletDetailTypeEnum.valueOf(walletDetail.getType()));
        walletDetailEntity.setAmount(walletDetail.getAmount());
        walletDetailEntity.setOrderId(walletDetail.getOrderId());
        walletDetailEntity.setBizRound(walletDetail.getBizRound());
        walletDetailEntity.setCreateTime(walletDetail.getCreateTime());
        return walletDetailEntity;
    }

    /** 平台侧孤儿扫描输入（平台有入账无出账 = 平台已收但未结算 / 退款），只读，无事务 */
    @Override
    public List<WalletDetailEntity> listOrphanPlatformIn(int thresholdMinutes, int limit) {
        return walletDetailDao.selectOrphanPlatformIn(WalletDetailTypeEnum.PLATFORM_IN.name(),
                        WalletDetailTypeEnum.PLATFORM_OUT.name(), thresholdMinutes, limit)
                .stream().map(this::toDetailEntity).toList();
    }

    // ---------- 支付回调任务（wallet_pay_task，域内最终一致任务，ADR-0017） ----------

    @Override
    public List<WalletPayTaskEntity> listDuePayTasks(int limit) {
        return walletPayTaskDao.listDue(limit).stream().map(this::toPayTaskEntity).toList();
    }

    @Override
    public boolean claimPayTask(Long id, LocalDateTime nextRetryAt) {
        return walletPayTaskDao.claim(id, nextRetryAt) > 0;
    }

    @Override
    public void markPayTaskDone(String orderId) {
        walletPayTaskDao.markDone(orderId);
    }

    @Override
    public void markPayTaskDead(Long id) {
        walletPayTaskDao.markDead(id);
    }

    private WalletPayTaskEntity toPayTaskEntity(WalletPayTaskPO po) {
        WalletPayTaskEntity entity = new WalletPayTaskEntity();
        entity.setId(po.getId());
        entity.setOrderId(po.getOrderId());
        entity.setState(WalletPayTaskStateEnum.of(po.getState()));
        entity.setRetryCount(po.getRetryCount());
        entity.setNextRetryAt(po.getNextRetryAt());
        entity.setFinishTime(po.getFinishTime());
        entity.setCreateTime(po.getCreateTime());
        entity.setUpdateTime(po.getUpdateTime());
        return entity;
    }

    // ---------- 支付单（payment_order：模拟微信支付的统一下单 / 支付 / 关闭） ----------

    @Override
    public PaymentOrderEntity createPaymentOrder(String userId, String bizOrderId, BigDecimal amount, String subject) {
        PaymentOrderPO paymentOrderPO = new PaymentOrderPO();
        // 支付单号随 insert 一次写入；撞 uk_biz_order_id 时本行未落库，发号作废无害
        paymentOrderPO.setPayNo(idGenerator.nextId());
        paymentOrderPO.setBizOrderId(bizOrderId);
        paymentOrderPO.setUserId(userId);
        paymentOrderPO.setAmount(amount);
        paymentOrderPO.setSubject(subject);
        paymentOrderPO.setState(PaymentOrderStateEnum.WAIT_PAY.name());
        return transactionTemplate.execute(status -> {
            paymentOrderDao.insertIgnore(paymentOrderPO);
            return toPaymentOrderEntity(paymentOrderDao.selectByBizOrderId(bizOrderId));
        });
    }

    @Override
    public PaymentOrderEntity getPaymentOrderByPayNo(String payNo) {
        return toPaymentOrderEntity(paymentOrderDao.selectByPayNo(payNo));
    }

    @Override
    public PaymentOrderEntity getPaymentOrderByBizOrderId(String bizOrderId) {
        return toPaymentOrderEntity(paymentOrderDao.selectByBizOrderId(bizOrderId));
    }

    /**
     * 支付单支付：支付单置 PAID + 买家出账流水 + 平台入账流水 + 买家余额条件扣减 + 平台余额累加
     * + 回调任务行，一个钱包事务。
     * <p>
     * 幂等锚是买家那一行 {@code PRODUCT_PAY}：它 0 行（uk 冲突）说明这笔支付早处理过，
     * 直接按「已支付」返回、不动任何余额。支付单已 PAID 的重复支付同样幂等返回（不动资金），
     * 供重复支付 / 回调丢失后的重试。
     */
    @Override
    public PaymentPayResult pay(String payNo, String userId) {
        return transactionTemplate.execute(status -> {
            // 平台钱包行先就位：下面 PLATFORM_IN 流水是 INSERT ... SELECT FROM virtual_wallet
            ensurePlatformWallet();
            PaymentOrderPO paymentOrderPO = paymentOrderDao.selectByPayNo(payNo);
            if (paymentOrderPO == null) {
                throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "支付单不存在");
            }
            if (!paymentOrderPO.getUserId().equals(userId)) {
                throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能支付自己的订单");
            }
            PaymentOrderStateEnum state = PaymentOrderStateEnum.of(paymentOrderPO.getState());
            if (state == PaymentOrderStateEnum.CLOSED) {
                throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "支付单已关闭");
            }
            if (state == PaymentOrderStateEnum.PAID) {
                return toPayResult(paymentOrderPO, true);
            }
            // 支付单状态先行：并发双支付只有一个能抢到 WAIT_PAY → PAID
            if (paymentOrderDao.markPaid(payNo) == 0) {
                PaymentOrderPO latest = paymentOrderDao.selectByPayNoForUpdate(payNo);
                if (latest != null && PaymentOrderStateEnum.of(latest.getState()) == PaymentOrderStateEnum.PAID) {
                    return toPayResult(latest, true);
                }
                throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "支付单状态已变化，请刷新重试");
            }
            // 双边流水先行：买家出账行是幂等锚（uk 冲突 = 这笔支付已处理过，整笔返回、不动任何余额）
            WalletDetail buyerDetail = new WalletDetail();
            buyerDetail.setUserId(userId);
            buyerDetail.setType(WalletDetailTypeEnum.PRODUCT_PAY.name());
            buyerDetail.setAmount(paymentOrderPO.getAmount());
            buyerDetail.setOrderId(paymentOrderPO.getBizOrderId());
            buyerDetail.setBizRound(0);
            if (walletDetailDao.insertIgnoreDetail(buyerDetail) == 0) {
                return toPayResult(paymentOrderPO, true);
            }
            // 平台代收入账行：与买家出账行同事务成对
            insertPlatformDetail(WalletDetailTypeEnum.PLATFORM_IN, paymentOrderPO.getBizOrderId(), paymentOrderPO.getAmount());
            // 再动双边余额：买家可用余额（balance - frozen）不足 → 抛 400，整笔回滚（支付单留 WAIT_PAY）。
            // 商品链路不写 frozen_amount：钱在支付时就真的离开买家账户，历史冻结额只在守卫里作减项
            if (walletDao.updateDecreaseAvailableBalance(userId, paymentOrderPO.getAmount()) == 0) {
                throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "零钱余额不足，无法完成支付");
            }
            walletDao.updateIncreaseBalance(Constants.PLATFORM_USER_ID, paymentOrderPO.getAmount());
            // 回调任务行与支付同事务：任务在 ⇒ 钱已过账、支付单已 PAID；回调失败由 productPayTaskScan 重放
            walletPayTaskDao.insertIgnore(newPayTask(paymentOrderPO.getBizOrderId()));
            return toPayResult(paymentOrderPO, false);
        });
    }

    @Override
    public void closePaymentOrder(String bizOrderId) {
        paymentOrderDao.closeByBizOrderId(bizOrderId);
    }

    private PaymentPayResult toPayResult(PaymentOrderPO paymentOrderPO, boolean alreadyPaid) {
        return new PaymentPayResult(paymentOrderPO.getPayNo(), paymentOrderPO.getBizOrderId(),
                paymentOrderPO.getUserId(), paymentOrderPO.getAmount(), alreadyPaid);
    }

    private PaymentOrderEntity toPaymentOrderEntity(PaymentOrderPO paymentOrderPO) {
        if (paymentOrderPO == null) {
            return null;
        }
        PaymentOrderEntity paymentOrderEntity = new PaymentOrderEntity();
        paymentOrderEntity.setPayNo(paymentOrderPO.getPayNo());
        paymentOrderEntity.setBizOrderId(paymentOrderPO.getBizOrderId());
        paymentOrderEntity.setUserId(paymentOrderPO.getUserId());
        paymentOrderEntity.setAmount(paymentOrderPO.getAmount());
        paymentOrderEntity.setSubject(paymentOrderPO.getSubject());
        paymentOrderEntity.setState(PaymentOrderStateEnum.of(paymentOrderPO.getState()));
        paymentOrderEntity.setPayTime(paymentOrderPO.getPayTime());
        paymentOrderEntity.setCreatedAt(paymentOrderPO.getCreateTime());
        return paymentOrderEntity;
    }
}
