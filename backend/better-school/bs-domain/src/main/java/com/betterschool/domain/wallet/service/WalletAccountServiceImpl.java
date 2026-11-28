package com.betterschool.domain.wallet.service;

import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.model.entity.WalletPayTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    public void ensureWallet(String userId, BigDecimal initialBalance) {
        walletRepository.ensureWallet(userId, initialBalance);
    }

    @Override
    public void recharge(String userId, BigDecimal amount) {
        walletRepository.recharge(userId, amount);
    }

    @Override
    public void markPayTaskDone(String orderId) {
        walletRepository.markPayTaskDone(orderId);
    }

    @Override
    public List<WalletDetailEntity> listOrphanPlatformIn(int thresholdMinutes, int limit) {
        return walletRepository.listOrphanPlatformIn(thresholdMinutes, limit);
    }

    @Override
    public List<WalletPayTaskEntity> listDuePayTasks(int limit) {
        return walletRepository.listDuePayTasks(limit);
    }

    @Override
    public boolean claimPayTask(Long id, LocalDateTime nextRetryAt) {
        return walletRepository.claimPayTask(id, nextRetryAt);
    }

    @Override
    public void markPayTaskDead(Long id) {
        walletRepository.markPayTaskDead(id);
    }

    @Override
    public void settleProductOrder(String orderId, String sellerId, BigDecimal amount) {
        walletRepository.settleProductOrder(orderId, sellerId, amount);
    }

    @Override
    public void refundProductOrder(String orderId, String buyerId, BigDecimal amount) {
        walletRepository.refundProductOrder(orderId, buyerId, amount);
    }

    @Override
    public VirtualWalletEntity getBalance(String userId) {
        VirtualWalletEntity virtualWalletEntity = walletRepository.getByUserId(userId);
        if (virtualWalletEntity == null) {
            // 兜底自愈：注册成功但建钱包失败的存量用户，查余额时补建
            walletRepository.ensureWallet(userId);
            virtualWalletEntity = walletRepository.getByUserId(userId);
        }
        return virtualWalletEntity;
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
