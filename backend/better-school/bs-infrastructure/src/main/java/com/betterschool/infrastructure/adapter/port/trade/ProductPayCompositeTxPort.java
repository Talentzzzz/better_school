package com.betterschool.infrastructure.adapter.port.trade;

import com.betterschool.domain.trade.adapter.port.IProductPayCompositeTxPort;
import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.valobj.ProductPayResult;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.infrastructure.tx.CompositeTxExecutor;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 商品支付组合事务端口的实现（资金一致性票 06），组合事务边界落在
 * {@link CompositeTxExecutor#execute}（一个 REQUIRED 物理事务，参与域仓储同为 REQUIRED 自动并入）：
 * <ol>
 *   <li>消费方域（trade）自己的动作：{@code ITradeRepository.payOrderById} 条件翻转
 *       「待支付 → 已支付」（守卫买家 + 前态，未命中抛异常整事务回滚）；</li>
 *   <li>经端口委托钱包域：{@code IWalletAccountService.freeze}（PRODUCT_FROZEN）——流水先行
 *       （uk_order_type 幂等）+ 余额条件扣减，余额不足抛异常整事务回滚。</li>
 * </ol>
 * 「订单已支付 ⇒ 货款已冻结」在这一个物理事务里成立；为什么组合事务在基础设施端口而非
 * case 层或域服务，同 {@code PointsDeductPort} 先例（docs/adr/0014）：域服务不注入事务模板、
 * case 层不包多域事务，事务边界归基础设施。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductPayCompositeTxPort implements IProductPayCompositeTxPort {

    private final CompositeTxExecutor compositeTxExecutor;
    private final ITradeRepository tradeRepository;
    private final IWalletAccountService walletAccountService;

    @Override
    public ProductPayResult payOrder(String orderId, String buyerId, BigDecimal amount) {
        return compositeTxExecutor.execute(() -> {
            // 同事务第一步：订单「待支付 → 已支付」条件翻转（PAID 事件随行落表）
            if (!tradeRepository.payOrderById(orderId, buyerId)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
            }
            // 同事务第二步：货款冻结（流水先行 PRODUCT_FROZEN + 余额条件扣减，不足抛异常整事务回滚）
            walletAccountService.freeze(buyerId, amount, orderId, WalletDetailTypeVO.PRODUCT_FROZEN);
            return new ProductPayResult(amount);
        });
    }
}
