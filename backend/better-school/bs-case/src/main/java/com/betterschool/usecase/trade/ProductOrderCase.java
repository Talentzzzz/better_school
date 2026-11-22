package com.betterschool.usecase.trade;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductConfirmResult;
import com.betterschool.domain.trade.service.productorder.IProductOrderService;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品订单用例编排：跨域组合顺序调用（每个域各自事务原子），
 * 一致性靠"幂等 + 重试"——状态机条件 UPDATE / wallet 流水 uk_order_type 保证重试安全，本层不做事务；
 * 支付与取消链路经域服务组合事务端口收口（票 06 支付 / 票 07 取消）：订单翻转与货款冻结 /
 * 解冻退款同物理事务，本层单次调用即可
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

    /** 商品支付（票 06 收口）：翻转 + 冻结在域服务组合事务端口内一个物理事务完成，余额不足整事务回滚 */
    public void payOrder(String orderId, String buyerId) {
        productOrderService.payOrder(orderId, buyerId);
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
     * 取消（资金收口，票 07）：买家解冻退款与取消翻转已由 IProductPayCompositeTxPort
     * 并入一个物理事务（见 ProductOrderServiceImpl.cancelOrder），本层不再拆两步调用——
     * 「订单已取消但货款悬置冻结态」的中间态由该事务消灭；
     * 待支付取消（未冻结）在域服务走无资金动作分支，重放终态短路不重复退款
     */
    public void cancelOrder(String orderId, String buyerId) {
        productOrderService.cancelOrder(orderId, buyerId);
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
