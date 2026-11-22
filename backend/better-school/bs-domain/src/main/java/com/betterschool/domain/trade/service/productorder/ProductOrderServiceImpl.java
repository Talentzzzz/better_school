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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品托管订单服务：5 态订单状态机 + 库存台账扣减/回补（multi-quantity-listing，同域仓储同事务），
 * 全部条件 UPDATE 防并发（防超卖唯一闸门在台账 remaining 的条件扣减上）；
 * 跨表原子写（订单 + 台账扣减 + 流水 + 搜索同步 + 超时注册）收成 ITradeRepository 的原子用例，事务在仓储实现内；
 * 支付/取消的资金链路为两段式（ADR-0016）：本域只做判据与状态翻转（翻转段，单仓储事务），
 * 资金段（冻结/解冻）由 case 层按结果对象顺序执行——支付资金段先行（余额不足订单留待支付），
 * 取消翻转段先行（翻转未命中零残留），宕机窗口的长款中间态由 {@code productFundOrphanScan} 收敛
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductOrderServiceImpl implements IProductOrderService {

    private static final int PAY_TIMEOUT_MINUTES = 15;

    /** 单笔订单件数上限（同款多件挂单的买入上限，防误操作巨量单） */
    private static final int MAX_ORDER_QUANTITY = 999;

    private final ITradeRepository tradeRepository;

    @Override
    public String createOrder(String buyerId, String productId, int quantity) {
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "购买件数须在 1.." + MAX_ORDER_QUANTITY + " 之间");
        }
        ProductEntity productEntity = getAvailableProduct(productId);
        if (productEntity.isOwner(buyerId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "不能购买自己发布的商品");
        }
        ProductOrderEntity orderEntity = ProductOrderEntity.create(buyerId, productEntity, quantity);
        // 域内事务（仓储内）：订单 + 台账扣减 + 流水 + 售罄翻态 + 超时注册原子
        return tradeRepository.createOrderWithInventory(orderEntity, productId, PAY_TIMEOUT_MINUTES);
    }

    @Override
    public ProductPayResult preparePayOrder(String orderId, String buyerId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能支付自己的订单");
        }
        // 幂等判定：已支付（PAID）及其后态视为上次支付已提交成功（重试场景），alreadyPaid 零副作用
        if (orderEntity.getStatus() == ProductOrderStatusVO.PAID
                || orderEntity.getStatus() == ProductOrderStatusVO.DELIVERED
                || orderEntity.getStatus() == ProductOrderStatusVO.COMPLETED) {
            return new ProductPayResult(totalAmount(orderEntity), true);
        }
        if (orderEntity.getStatus() != ProductOrderStatusVO.PENDING_PAYMENT) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或不可支付");
        }
        // 支付只翻订单状态：库存已在下单时扣减（售罄已随行翻 SOLD），无商品状态联动与搜索同步
        return new ProductPayResult(totalAmount(orderEntity), false);
    }

    @Override
    public void confirmPayOrder(String orderId, String buyerId) {
        // 两段式 ADR-0016 翻转段（资金段冻结已由 case 层先行完成，流水 uk 幂等）：
        // 「待支付 → 已支付」条件翻转（守卫买家 + 前态，PAID 事件随行落表，单仓储事务）。
        // 翻转未命中 → 抛业务异常；此刻货款可能已冻结（订单留待支付的长款中间态），
        // 孤儿由 productFundOrphanScan 扫描解冻收敛
        if (!tradeRepository.payOrderById(orderId, buyerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
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
            return new ProductConfirmResult(orderEntity.getBuyerId(), orderEntity.getSellerId(), totalAmount(orderEntity));
        }
        if (orderEntity.getStatus() != ProductOrderStatusVO.DELIVERED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在或未交付");
        }
        // 确认收货只翻订单状态：库存已扣、售罄已翻态，无商品状态联动与搜索同步
        if (!tradeRepository.confirmOrderById(orderId, buyerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        return new ProductConfirmResult(orderEntity.getBuyerId(), orderEntity.getSellerId(), totalAmount(orderEntity));
    }

    @Override
    public ProductCancelResult cancelOrder(String orderId, String buyerId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有买家可以取消订单");
        }
        // 幂等判定（重试场景）：已取消/已完成视为本次取消已提交成功（终态短路，零副作用；
        // 资金段的补齐由 productFundOrphanScan 扫描兜底）
        if (orderEntity.getStatus() == ProductOrderStatusVO.CANCELLED
                || orderEntity.getStatus() == ProductOrderStatusVO.COMPLETED) {
            return new ProductCancelResult(false, orderEntity.getBuyerId(), totalAmount(orderEntity));
        }
        if (orderEntity.getStatus() == ProductOrderStatusVO.DELIVERED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "已交付后不可取消，请确认收货");
        }
        // 待支付未冻结无需退款；已支付需解冻退回
        boolean needRefund = orderEntity.getStatus() == ProductOrderStatusVO.PAID;
        // 翻转段先行（两段式 ADR-0016，与跑腿取消同型）：取消翻转（含台账回补 + 搜索同步）
        // 单仓储事务，未命中抛异常——资金段尚未执行，零残留；翻转与 case 层解冻之间的宕机孤儿
        // （订单已取消但货款悬置冻结，长款中间态）由 productFundOrphanScan 扫描收敛
        if (!tradeRepository.cancelOrderWithSearchSync(orderId, buyerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        return new ProductCancelResult(needRefund, orderEntity.getBuyerId(), totalAmount(orderEntity));
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

    /** 订单总金额 = 成交单价快照 × 成交件数（钱包冻结/解冻/入账统一按总额走） */
    private static BigDecimal totalAmount(ProductOrderEntity orderEntity) {
        int quantity = orderEntity.getQuantity() == null ? 1 : orderEntity.getQuantity();
        return orderEntity.getPrice().multiply(BigDecimal.valueOf(quantity));
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
