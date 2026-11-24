package com.betterschool.usecase.idle;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductConfirmResult;
import com.betterschool.domain.idle.service.productorder.IProductOrderService;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品订单用例编排：跨域组合顺序调用（每个域各自事务原子，事务边界 = 仓储，ADR-0016），
 * 一致性靠"幂等 + 重试"——状态机条件 UPDATE / wallet 流水 uk_order_type 保证重试安全，本层不做事务；
 * 支付与取消为两段式资金链路：支付资金段（冻结）先行、订单翻转在后；取消翻转段先行、本层补解冻退款；
 * 宕机窗口的长款中间态由 {@code productFundOrphanScan} 孤儿扫描收敛
 */
@Service
@RequiredArgsConstructor
public class ProductOrderCase {

    private final IProductOrderService productOrderService;
    private final ICreditAccountService creditAccountService;
    private final IWalletAccountService walletAccountService;

    public String createOrder(String buyerId, String productId, int quantity) {
        creditAccountService.ensureOperable(buyerId);
        return productOrderService.createOrder(buyerId, productId, quantity);
    }

    /**
     * 支付（两段式 ADR-0016）：① 域服务 prepare 校验（买家/状态幂等，返回需冻结货款）→
     * ② 本层资金段冻结货款（PRODUCT_FROZEN，流水 uk_order_type 幂等 + 余额条件扣减，
     * 余额不足抛异常、订单留待支付）→ ③ 域服务 confirm 翻转「待支付 → 已支付」（单仓储事务）。
     * 资金段恒先行（宁可长款不可短款）：翻转失败时订单留待支付、钱悬置冻结，
     * 孤儿由 {@code productFundOrphanScan} 扫描解冻收敛。客户端重试安全：prepare 幂等回放 +
     * 冻结流水 uk 吸收重复冻结
     */
    public void payOrder(String orderId, String buyerId) {
        var payResult = productOrderService.preparePayOrder(orderId, buyerId);
        if (payResult.isAlreadyPaid()) {
            return; // 幂等：上次支付已提交成功（重试场景），零副作用
        }
        walletAccountService.freeze(buyerId, payResult.getAmount(), orderId, WalletDetailTypeVO.PRODUCT_FROZEN);
        productOrderService.confirmPayOrder(orderId, buyerId);
    }

    public void markDelivered(String orderId, String sellerId) {
        productOrderService.markDelivered(orderId, sellerId);
    }

    public void confirmReceived(String orderId, String buyerId) {
        ProductConfirmResult confirmResult = productOrderService.confirmReceived(orderId, buyerId);
        walletAccountService.unfreeze(confirmResult.getBuyerId(), confirmResult.getAmount(), orderId,
                WalletDetailTypeVO.PRODUCT_UNFROZEN);
        walletAccountService.income(confirmResult.getSellerId(), confirmResult.getAmount(), orderId,
                WalletDetailTypeVO.PRODUCT_INCOME);
    }

    /**
     * 取消（两段式 ADR-0016）：域服务只做取消翻转段（含台账回补 + 搜索同步，单仓储事务，
     * 翻转段先行——翻转未命中抛异常、资金段尚未执行零残留），本层按结果对象的退款需求
     * 顺序执行解冻退款（PRODUCT_UNFROZEN，流水 uk_order_type 幂等）；
     * 翻转与解冻之间的宕机孤儿（订单已取消但货款悬置冻结态）由 {@code productFundOrphanScan}
     * 扫描收敛；待支付取消（未冻结）needRefund=false 无资金动作，重放终态短路不重复退款
     */
    public void cancelOrder(String orderId, String buyerId) {
        var cancelResult = productOrderService.cancelOrder(orderId, buyerId);
        if (cancelResult.isNeedRefund()) {
            walletAccountService.unfreeze(cancelResult.getBuyerId(), cancelResult.getAmount(), orderId,
                    WalletDetailTypeVO.PRODUCT_UNFROZEN);
        }
    }

    public ProductOrderEntity orderDetail(String orderId, String currentUserId) {
        return productOrderService.orderDetail(orderId, currentUserId);
    }

    public List<ProductOrderEntity> listMineOrders(String role, String userId, int page, int pageSize) {
        return productOrderService.listMineOrders(role, userId, page, pageSize);
    }

    public long countMineOrders(String role, String userId) {
        return productOrderService.countMineOrders(role, userId);
    }

    public void handlePayTimeout(String orderId) {
        productOrderService.handlePayTimeout(orderId);
    }

}
