package com.betterschool.infrastructure.adapter.repository.wallet;

import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.infrastructure.dao.wallet.IWalletDao;
import com.betterschool.infrastructure.dao.wallet.IWalletDetailDao;
import com.betterschool.infrastructure.dao.wallet.po.VirtualWallet;
import com.betterschool.infrastructure.dao.wallet.po.WalletDetail;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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

    private final IWalletDao walletDao;
    /** 钱包流水（wallet_detail）按表拆分的 DAO */
    private final IWalletDetailDao walletDetailDao;

    /** 资金动作都是「流水先行 + 余额条件 UPDATE」的原子用例：仓储方法内用事务（REQUIRED）收口 */
    private final TransactionTemplate transactionTemplate;

    @Override
    public void ensureWallet(String userId) {
        VirtualWallet wallet = new VirtualWallet();
        // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；INSERT IGNORE 撞 uk_user 时本行未落库，发号作废无害
        wallet.setWalletId(UUID.randomUUID().toString());
        wallet.setUserId(userId);
        wallet.setBalance(INITIAL_BALANCE);
        wallet.setFrozenAmount(BigDecimal.ZERO);
        walletDao.insertIgnore(wallet);
    }

    @Override
    public VirtualWalletEntity getByUserId(String userId) {
        return toEntity(walletDao.selectByUserId(userId));
    }

    /** 流水 + 余额一个原子用例 */
    @Override
    public void recharge(String userId, BigDecimal amount) {
        transactionTemplate.execute(status -> {
            // RECHARGE 无 order_id，不受 uk 约束（mock 充值不要求幂等）
            if (insertDetail(userId, WalletDetailTypeVO.RECHARGE, amount, null, 0) == 0) {
                return null;
            }
            walletDao.updateIncreaseBalance(userId, amount);
            return null;
        });
    }

    @Override
    public void freeze(String userId, BigDecimal amount, String orderId) {
        freeze(userId, amount, orderId, WalletDetailTypeVO.FROZEN);
    }

    @Override
    public void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type) {
        freeze(userId, amount, orderId, type, 0);
    }

    /** 流水先行 + 余额条件 UPDATE 一个原子用例（余额不足时流水随之回滚） */
    @Override
    public void freeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type, int bizRound) {
        transactionTemplate.execute(status -> {
            // 流水先行：uk_order_type 冲突（已冻结过）→ 幂等跳过，不动余额
            if (insertDetail(userId, type, amount, orderId, bizRound) == 0) {
                return null;
            }
            // 可用余额（balance - frozen）不足则冻结失败，事务整体回滚（含流水）
            if (walletDao.updateFreeze(userId, amount) == 0) {
                throw new BusinessException(ResponseCode.PARAM_ERROR, "余额不足，无法完成操作");
            }
            return null;
        });
    }

    @Override
    public void unfreeze(String userId, BigDecimal amount, String orderId) {
        unfreeze(userId, amount, orderId, WalletDetailTypeVO.UNFROZEN);
    }

    @Override
    public void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type) {
        unfreeze(userId, amount, orderId, type, 0);
    }

    /** 流水先行 + 解冻回退一个原子用例 */
    @Override
    public void unfreeze(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type, int bizRound) {
        transactionTemplate.execute(status -> {
            if (insertDetail(userId, type, amount, orderId, bizRound) == 0) {
                return null;
            }
            walletDao.updateUnfreeze(userId, amount);
            return null;
        });
    }

    @Override
    public void income(String userId, BigDecimal amount, String orderId) {
        income(userId, amount, orderId, WalletDetailTypeVO.INCOME);
    }

    /** 流水先行 + 余额入账一个原子用例 */
    @Override
    public void income(String userId, BigDecimal amount, String orderId, WalletDetailTypeVO type) {
        transactionTemplate.execute(status -> {
            if (insertDetail(userId, type, amount, orderId, 0) == 0) {
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

    /** 返回影响行数：0 = 该流水已存在（幂等跳过） */
    private int insertDetail(String userId, WalletDetailTypeVO type, BigDecimal amount, String orderId, int bizRound) {
        WalletDetail detail = new WalletDetail();
        detail.setUserId(userId);
        detail.setType(type.name());
        detail.setAmount(amount);
        detail.setOrderId(orderId);
        detail.setBizRound(bizRound);
        return walletDetailDao.insertIgnoreDetail(detail);
    }

    private VirtualWalletEntity toEntity(VirtualWallet wallet) {
        if (wallet == null) {
            return null;
        }
        VirtualWalletEntity walletEntity = new VirtualWalletEntity();
        walletEntity.setWalletId(wallet.getWalletId());
        walletEntity.setUserId(wallet.getUserId());
        walletEntity.setBalance(wallet.getBalance());
        walletEntity.setFrozenAmount(wallet.getFrozenAmount());
        walletEntity.setCreatedAt(wallet.getCreateTime());
        return walletEntity;
    }

    private WalletDetailEntity toDetailEntity(WalletDetail detail) {
        WalletDetailEntity detailEntity = new WalletDetailEntity();
        detailEntity.setId(detail.getId());
        detailEntity.setWalletId(detail.getWalletId());
        detailEntity.setUserId(detail.getUserId());
        detailEntity.setType(WalletDetailTypeVO.valueOf(detail.getType()));
        detailEntity.setAmount(detail.getAmount());
        detailEntity.setOrderId(detail.getOrderId());
        detailEntity.setBizRound(detail.getBizRound());
        detailEntity.setCreateTime(detail.getCreateTime());
        return detailEntity;
    }

    /** 孤儿扫描输入（ADR-0016 两段式）：流水类型成对传入（按域各一条扫描线），只读，无事务 */
    @Override
    public List<WalletDetailEntity> listOrphanFrozen(WalletDetailTypeVO frozenType, WalletDetailTypeVO unfrozenType,
                                                     int thresholdMinutes, int limit) {
        return walletDetailDao.selectOrphanFrozen(frozenType.name(), unfrozenType.name(), thresholdMinutes, limit)
                .stream().map(this::toDetailEntity).toList();
    }
}
