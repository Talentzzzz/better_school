package com.betterschool.domain.wallet.service;

import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 虚拟钱包服务：每个资金操作（流水 + 余额）是一次仓储原子用例；
 * 跨域组合由 case 层顺序调用，重试安全靠流水 uk_order_type 幂等
 */
@Service
@RequiredArgsConstructor
public class WalletAccountServiceImpl implements IWalletAccountService {

    private final IWalletRepository walletRepository;

    @Override
    public void ensureWallet(String userId) {
        walletRepository.ensureWallet(userId);
    }

    @Override
    public void recharge(String userId, BigDecimal amount) {
        walletRepository.recharge(userId, amount);
    }

    @Override
    public void freeze(String userId, BigDecimal amount, Long orderId) {
        freeze(userId, amount, orderId, WalletDetailTypeVO.FROZEN);
    }

    @Override
    public void freeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type) {
        freeze(userId, amount, orderId, type, 0);
    }

    @Override
    public void freeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type, int bizRound) {
        walletRepository.freeze(userId, amount, orderId, type, bizRound);
    }

    @Override
    public void unfreeze(String userId, BigDecimal amount, Long orderId) {
        unfreeze(userId, amount, orderId, WalletDetailTypeVO.UNFROZEN);
    }

    @Override
    public void unfreeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type) {
        unfreeze(userId, amount, orderId, type, 0);
    }

    @Override
    public void unfreeze(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type, int bizRound) {
        walletRepository.unfreeze(userId, amount, orderId, type, bizRound);
    }

    @Override
    public void income(String userId, BigDecimal amount, Long orderId) {
        income(userId, amount, orderId, WalletDetailTypeVO.INCOME);
    }

    @Override
    public void income(String userId, BigDecimal amount, Long orderId, WalletDetailTypeVO type) {
        walletRepository.income(userId, amount, orderId, type);
    }

    @Override
    public VirtualWalletEntity getBalance(String userId) {
        VirtualWalletEntity walletEntity = walletRepository.getByUserId(userId);
        if (walletEntity == null) {
            // 兜底自愈：注册成功但建钱包失败的存量用户，查余额时补建
            walletRepository.ensureWallet(userId);
            walletEntity = walletRepository.getByUserId(userId);
        }
        return walletEntity;
    }

    @Override
    public List<WalletDetailEntity> listDetails(String userId, int page, int pageSize) {
        return walletRepository.listDetails(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countDetails(String userId) {
        return walletRepository.countDetails(userId);
    }
}
