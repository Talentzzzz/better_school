package com.betterschool.integration.support;

import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeEnum;
import com.betterschool.infrastructure.adapter.repository.wallet.WalletRepository;
import com.betterschool.infrastructure.dao.wallet.IPaymentOrderDao;
import com.betterschool.infrastructure.dao.wallet.IWalletDao;
import com.betterschool.infrastructure.dao.wallet.IWalletDetailDao;
import com.betterschool.infrastructure.dao.wallet.IWalletPayTaskDao;
import com.betterschool.types.id.IIdGenerator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * 钱包资金动作的失败注入探针（资金收口票 05）：{@code @Primary} 顶掉生产钱包仓储，
 * 让用例能在**端口委托的钱包动作边界**上注入失败——默认透传真仓储，既有用例行为一字不变；
 * 只有显式摆了桩的用例才在入账那一刻抛异常，用来断言资金动作「任一步失败整事务回滚」的外显。
 *
 * <p>为什么要探针而不是数据态注入：入账（流水先行 + 余额无条件累加）没有余额不足那类
 * 自然失败面（不同于冻结的条件扣减），无法靠压余额让它在自动完成链路上失配——
 * 唯一能确定性触发「钱包动作抛异常」的缝就是探针（同 {@code TimeoutTaskCallbackProbe} 的先例）。
 */
@Component
@Primary
public class WalletIncomeFailureProbe extends WalletRepository {

    private volatile boolean incomeFailureArmed = false;

    public WalletIncomeFailureProbe(IWalletDao walletDao, IWalletDetailDao walletDetailDao,
                                    IWalletPayTaskDao walletPayTaskDao, IPaymentOrderDao paymentOrderDao,
                                    IIdGenerator idGenerator, TransactionTemplate transactionTemplate) {
        super(walletDao, walletDetailDao, walletPayTaskDao, paymentOrderDao, idGenerator, transactionTemplate);
    }

    /** 摆桩：下一次 income 调用直接抛异常（组合事务内 → 整物理事务回滚） */
    public void armIncomeFailure() {
        incomeFailureArmed = true;
    }

    /** 回到默认透传并清空桩 */
    public void reset() {
        incomeFailureArmed = false;
    }

    @Override
    public void income(String userId, BigDecimal amount, String orderId, WalletDetailTypeEnum walletDetailTypeVO) {
        if (incomeFailureArmed) {
            throw new IllegalStateException("探针：注入接单方入账失败 orderId=" + orderId);
        }
        super.income(userId, amount, orderId, walletDetailTypeVO);
    }
}
