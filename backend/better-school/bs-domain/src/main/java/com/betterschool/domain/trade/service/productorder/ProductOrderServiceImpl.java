package com.betterschool.domain.trade.service.productorder;

import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductCancelResult;
import com.betterschool.domain.trade.model.valobj.ProductConfirmResult;
import com.betterschool.domain.trade.model.valobj.ProductOrderStatusVO;
import com.betterschool.domain.trade.model.valobj.ProductPayResult;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品托管订单服务：5 态状态机 + 商品锁（同域仓储，同事务），全部条件 UPDATE 防并发；
 * 跨域动作（资金冻结/解冻/入账）不在此执行——通过结果对象返回，由 case 层编排。
 * 跨两表的原子写（订单 + 商品锁 + 搜索同步 + 超时注册）收成 ITradeRepository 的原子用例，事务在仓储实现内
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductOrderServiceImpl implements IProductOrderService {

    private static final int PAY_TIMEOUT_MINUTES = 15;

    private final ITradeRepository tradeRepository;

    @Override
    public String createOrder(String buyerId, String productId) {
        ProductEntity productEntity = getAvailableProduct(productId);
        if (productEntity.isOwner(buyerId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "不能购买自己发布的商品");
        }
        ProductOrderEntity orderEntity = ProductOrderEntity.create(buyerId, productEntity);
        // 域内事务（仓储内）：订单 + 商品锁 + 搜索同步 + 超时注册四写原子
        return tradeRepository.createOrderWithLock(orderEntity, productId, PAY_TIMEOUT_MINUTES);
    }

    @Override
    public ProductPayResult payOrder(String orderId, String buyerId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能支付自己的订单");
        }
        // 幂等判定：已支付（PAID）视为上次支付已提交成功（重试场景），跳过直接返回
        if (orderEntity.getStatus() == ProductOrderStatusVO.PAID
                || orderEntity.getStatus() == ProductOrderStatusVO.DELIVERED
                || orderEntity.getStatus() == ProductOrderStatusVO.COMPLETED) {
            return new ProductPayResult(orderEntity.getPrice());
        }
        if (!tradeRepository.payOrderWithSearchSync(orderId, buyerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        return new ProductPayResult(orderEntity.getPrice());
    }

    @Override
    public void markDelivered(String orderId, String sellerId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isSeller(sellerId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有卖家可以标记交付");
        }
        if (!tradeRepository.markDeliveredOrderById(orderId, sellerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
    }

    @Override
    public ProductConfirmResult confirmReceived(String orderId, String buyerId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有买家可以确认收货");
        }
        // 幂等判定：已完成视为上次确认已提交成功（重试场景），跳过直接返回
        if (orderEntity.getStatus() == ProductOrderStatusVO.COMPLETED) {
            return new ProductConfirmResult(orderEntity.getBuyerId(), orderEntity.getSellerId(), orderEntity.getPrice());
        }
        if (orderEntity.getStatus() != ProductOrderStatusVO.DELIVERED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或未交付");
        }
        if (!tradeRepository.confirmOrderWithSearchSync(orderId, buyerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        return new ProductConfirmResult(orderEntity.getBuyerId(), orderEntity.getSellerId(), orderEntity.getPrice());
    }

    @Override
    public ProductCancelResult cancelOrder(String orderId, String buyerId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有买家可以取消订单");
        }
        // 幂等判定（重试场景）：已取消/已完成视为本次取消已提交成功
        if (orderEntity.getStatus() == ProductOrderStatusVO.CANCELLED
                || orderEntity.getStatus() == ProductOrderStatusVO.COMPLETED) {
            return new ProductCancelResult(false, orderEntity.getBuyerId(), orderEntity.getPrice());
        }
        if (orderEntity.getStatus() == ProductOrderStatusVO.DELIVERED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "已交付后不可取消，请确认收货");
        }
        // 待支付未冻结无需退款；已支付需解冻退回
        boolean needRefund = orderEntity.getStatus() == ProductOrderStatusVO.PAID;
        if (!tradeRepository.cancelOrderWithSearchSync(orderId, buyerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        return new ProductCancelResult(needRefund, orderEntity.getBuyerId(), orderEntity.getPrice());
    }

    @Override
    public ProductOrderEntity orderDetail(String orderId, String currentUserId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        // 仅交易双方（含卖家/买家）可见
        if (!orderEntity.isBuyer(currentUserId) && !orderEntity.isSeller(currentUserId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有交易双方可以查看订单");
        }
        return orderEntity;
    }

    @Override
    public List<ProductOrderEntity> listMineOrders(String role, String userId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        if ("buy".equals(role)) {
            return tradeRepository.listMineByBuyer(userId, offset, pageSize);
        }
        if ("sell".equals(role)) {
            return tradeRepository.listMineBySeller(userId, offset, pageSize);
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "role 不合法（buy/sell）");
    }

    @Override
    public long countMineOrders(String role, String userId) {
        if ("buy".equals(role)) {
            return tradeRepository.countMineByBuyer(userId);
        }
        if ("sell".equals(role)) {
            return tradeRepository.countMineBySeller(userId);
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "role 不合法（buy/sell）");
    }

    @Override
    public void handlePayTimeout(String orderId) {
        ProductOrderEntity orderEntity = tradeRepository.findOrderById(orderId);
        if (orderEntity == null || orderEntity.getStatus() != ProductOrderStatusVO.PENDING_PAYMENT) {
            // 已支付/已取消：幂等忽略
            return;
        }
        // 域内事务（仓储内）：订单取消 + 商品释放 + 搜索同步
        tradeRepository.cancelPendingPaymentWithSearchSync(orderId);
        log.info("[ProductPayTimeout] 待支付订单超时取消 orderId={}", orderId);
    }

    private ProductEntity getAvailableProduct(String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() != ProductStatusVO.ON_SALE) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在或已售出");
        }
        return productEntity;
    }

    private ProductOrderEntity getOrder(String orderId) {
        ProductOrderEntity orderEntity = tradeRepository.findOrderById(orderId);
        if (orderEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在");
        }
        return orderEntity;
    }
}
