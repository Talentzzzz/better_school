package com.betterschool.usecase.trade;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductCancelResult;
import com.betterschool.domain.trade.model.valobj.ProductConfirmResult;
import com.betterschool.domain.trade.model.valobj.ProductPayResult;
import com.betterschool.domain.trade.service.productorder.IProductOrderService;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品订单用例编排：跨域组合顺序调用（每个域各自事务原子），
 * 一致性靠"幂等 + 重试"——状态机条件 UPDATE / wallet 流水 uk_order_type 保证重试安全，本层不做事务
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

    public void payOrder(String orderId, String buyerId) {
        ProductPayResult payResult = productOrderService.payOrder(orderId, buyerId);
        walletAccountService.freeze(buyerId, payResult.getAmount(), orderId, WalletDetailTypeVO.PRODUCT_FROZEN);
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

    public void cancelOrder(String orderId, String buyerId) {
        ProductCancelResult cancelResult = productOrderService.cancelOrder(orderId, buyerId);
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
