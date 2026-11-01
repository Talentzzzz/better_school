package com.betterschool.infrastructure.adapter.repository.wallet;

import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.infrastructure.dao.wallet.IWalletDao;
import com.betterschool.infrastructure.dao.wallet.po.VirtualWalletPO;
import com.betterschool.infrastructure.dao.wallet.po.WalletDetailPO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 资金动作都是「流水先行 + 余额条件 UPDATE」的原子用例：仓储方法内用事务（REQUIRED）收口 */
    private final TransactionTemplate transactionTemplate;

    @Override
    public void ensureWallet(String userId) {
        VirtualWalletPO walletPO = new VirtualWalletPO();
        walletPO.setUserId(userId);
        walletPO.setBalance(INITIAL_BALANCE);
        walletPO.setFrozenAmount(BigDecimal.ZERO);
        LocalDateTime now = LocalDateTime.now();
        walletPO.setCreateTime(now);
        walletPO.setUpdateTime(now);
        walletDao.insertIgnore(walletPO);
        if (walletPO.getId() != null) {
            walletDao.bindBizId(walletPO.getId());
        }
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
    public void freeze(String userId, BigDecimal amount, Long orderId) {
        freeze(userId, amount, orderId, WalletDetailTypeVO.FROZEN);
    }

    @Override
    public void freeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type) {
        freeze(userId, amount, orderId, type, 0);
    }

    /** 流水先行 + 余额条件 UPDATE 一个原子用例（余额不足时流水随之回滚） */
    @Override
    public void freeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type, int bizRound) {
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
    public void unfreeze(String userId, BigDecimal amount, Long orderId) {
        unfreeze(userId, amount, orderId, WalletDetailTypeVO.UNFROZEN);
    }

    @Override
    public void unfreeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type) {
        unfreeze(userId, amount, orderId, type, 0);
    }

    /** 流水先行 + 解冻回退一个原子用例 */
    @Override
    public void unfreeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type, int bizRound) {
        transactionTemplate.execute(status -> {
            if (insertDetail(userId, type, amount, orderId, bizRound) == 0) {
                return null;
            }
            walletDao.updateUnfreeze(userId, amount);
            return null;
        });
    }

    @Override
    public void income(String userId, BigDecimal amount, Long orderId) {
        income(userId, amount, orderId, WalletDetailTypeVO.INCOME);
    }

    /** 流水先行 + 余额入账一个原子用例 */
    @Override
    public void income(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type) {
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
        return walletDao.selectDetails(userId, offset, limit).stream().map(this::toDetailEntity).toList();
    }

    @Override
    public long countDetails(String userId) {
        return walletDao.countDetails(userId);
    }

    /** 返回影响行数：0 = 该流水已存在（幂等跳过） */
    private int insertDetail(String userId, WalletDetailTypeVO type, BigDecimal amount, Long orderId, int bizRound) {
        WalletDetailPO detailPO = new WalletDetailPO();
        detailPO.setUserId(userId);
        detailPO.setType(type.name());
        detailPO.setAmount(amount);
        detailPO.setOrderId(orderId);
        detailPO.setBizRound(bizRound);
        LocalDateTime now = LocalDateTime.now();
        detailPO.setCreateTime(now);
        detailPO.setUpdateTime(now);
        return walletDao.insertIgnoreDetail(detailPO);
    }

    private VirtualWalletEntity toEntity(VirtualWalletPO walletPO) {
        if (walletPO == null) {
            return null;
        }
        VirtualWalletEntity walletEntity = new VirtualWalletEntity();
        walletEntity.setId(walletPO.getWalletId());
        walletEntity.setUserId(walletPO.getUserId());
        walletEntity.setBalance(walletPO.getBalance());
        walletEntity.setFrozenAmount(walletPO.getFrozenAmount());
        walletEntity.setCreatedAt(walletPO.getCreateTime());
        walletEntity.setUpdatedAt(walletPO.getUpdateTime());
        return walletEntity;
    }

    private WalletDetailEntity toDetailEntity(WalletDetailPO detailPO) {
        WalletDetailEntity detailEntity = new WalletDetailEntity();
        detailEntity.setId(detailPO.getId());
        detailEntity.setWalletId(detailPO.getWalletId());
        detailEntity.setUserId(detailPO.getUserId());
        detailEntity.setType(WalletDetailTypeVO.valueOf(detailPO.getType()));
        detailEntity.setAmount(detailPO.getAmount());
        detailEntity.setOrderId(detailPO.getOrderId());
        detailEntity.setBizRound(detailPO.getBizRound());
        detailEntity.setCreateTime(detailPO.getCreateTime());
        detailEntity.setUpdateTime(detailPO.getUpdateTime());
        return detailEntity;
    }
}
