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
 * 用事务（REQUIRED）收成一个原子用例——余额不足抛 PARAM_ERROR 时流水随之回滚
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WalletRepository implements IWalletRepository {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000");

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
        VirtualWallet virtualWallet = new VirtualWallet();
        // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；INSERT IGNORE 撞 uk_user 时本行未落库，发号作废无害
        virtualWallet.setWalletId(UUID.randomUUID().toString());
        virtualWallet.setUserId(userId);
        virtualWallet.setBalance(INITIAL_BALANCE);
        virtualWallet.setFrozenAmount(BigDecimal.ZERO);
        walletDao.insertIgnore(virtualWallet);
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

    @Override
    public void freeze(String userId, BigDecimal amount, String orderId) {
        freeze(userId, amount, orderId, WalletDetailTypeEnum.FROZEN);
    }

    /**
     * 流水先行 + 余额条件 UPDATE 一个原子用例（余额不足时流水随之回滚）。
     * <p>
     * 商品支付的冻结不走本方法：它由 {@link #pay(String, String)} 在支付事务内与支付单状态、
     * 回调任务行一起完成（钱已冻 ⇒ 支付单已 PAID、任务行在）。
     */
    @Override
    public void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO) {
        WalletDetail walletDetail = new WalletDetail();
        walletDetail.setUserId(userId);
        walletDetail.setType(walletDetailTypeVO.name());
        walletDetail.setAmount(amount);
        walletDetail.setOrderId(orderId);
        walletDetail.setBizRound(0);
        transactionTemplate.execute(status -> {
            // 流水先行：uk_order_type 冲突（已冻结过）→ 幂等跳过，不动余额
            if (walletDetailDao.insertIgnoreDetail(walletDetail) > 0) {
                // 可用余额（balance - frozen）不足则冻结失败，事务整体回滚（含流水）
                if (walletDao.updateFreeze(userId, amount) == 0) {
                    throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "余额不足，无法完成操作");
                }
            }
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

    @Override
    public void unfreeze(String userId, BigDecimal amount, String orderId) {
        unfreeze(userId, amount, orderId, WalletDetailTypeEnum.UNFROZEN);
    }

    @Override
    public void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO) {
        unfreeze(userId, amount, orderId, walletDetailTypeVO, 0);
    }

    /** 流水先行 + 解冻回退一个原子用例 */
    @Override
    public void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO, int bizRound) {
        WalletDetail walletDetail = new WalletDetail();
        walletDetail.setUserId(userId);
        walletDetail.setType(walletDetailTypeVO.name());
        walletDetail.setAmount(amount);
        walletDetail.setOrderId(orderId);
        walletDetail.setBizRound(bizRound);
        transactionTemplate.execute(status -> {
            if (walletDetailDao.insertIgnoreDetail(walletDetail) == 0) {
                return null;
            }
            walletDao.updateUnfreeze(userId, amount);
            return null;
        });
    }

    @Override
    public void income(String userId, BigDecimal amount, String orderId) {
        income(userId, amount, orderId, WalletDetailTypeEnum.INCOME);
    }

    /** 流水先行 + 余额入账一个原子用例 */
    @Override
    public void income(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO) {
        WalletDetail walletDetail = new WalletDetail();
        walletDetail.setUserId(userId);
        walletDetail.setType(walletDetailTypeVO.name());
        walletDetail.setAmount(amount);
        walletDetail.setOrderId(orderId);
        walletDetail.setBizRound(0);
        transactionTemplate.execute(status -> {
            if (walletDetailDao.insertIgnoreDetail(walletDetail) == 0) {
                return null;
            }
            walletDao.updateIncreaseBalance(userId, amount);
            return null;
        });
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

    /** 孤儿扫描输入（ADR-0016 两段式）：流水类型成对传入（按域各一条扫描线），只读，无事务 */
    @Override
    public List<WalletDetailEntity> listOrphanFrozen(WalletDetailTypeEnum frozenType, WalletDetailTypeEnum unfrozenType,
                                                     int thresholdMinutes, int limit) {
        return walletDetailDao.selectOrphanFrozen(frozenType.name(), unfrozenType.name(), thresholdMinutes, limit)
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
     * 支付单支付：支付单置 PAID + 冻结流水 + 余额条件扣减 + 回调任务行，一个钱包事务。
     * 支付单已是 PAID 时幂等返回（不动资金），供重复支付 / 回调丢失后的重试。
     */
    @Override
    public PaymentPayResult pay(String payNo, String userId) {
        return transactionTemplate.execute(status -> {
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
            // 冻结资金：流水先行（uk 幂等）+ 可用余额条件扣减；余额不足整体回滚（支付单留 WAIT_PAY）
            WalletDetail walletDetail = new WalletDetail();
            walletDetail.setUserId(userId);
            walletDetail.setType(WalletDetailTypeEnum.PRODUCT_FROZEN.name());
            walletDetail.setAmount(paymentOrderPO.getAmount());
            walletDetail.setOrderId(paymentOrderPO.getBizOrderId());
            walletDetail.setBizRound(0);
            if (walletDetailDao.insertIgnoreDetail(walletDetail) > 0 && walletDao.updateFreeze(userId, paymentOrderPO.getAmount()) == 0) {
                throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "零钱余额不足，无法完成支付");
            }
            // 回调任务行与支付同事务：任务在 ⇒ 钱已冻、支付单已 PAID；回调失败由 productPayTaskScan 重放
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
