package com.betterschool.domain.idle.service;

import com.betterschool.domain.idle.adapter.repository.IIdleRepository;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductCancelResult;
import com.betterschool.domain.idle.model.valobj.ProductCategoryVO;
import com.betterschool.domain.idle.model.valobj.ProductConfirmResult;
import com.betterschool.domain.idle.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.idle.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.domain.idle.model.valobj.ProductFeedPageVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusVO;
import com.betterschool.domain.idle.model.valobj.ProductPayResult;
import com.betterschool.domain.idle.model.valobj.ProductStatusVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 闲置域服务实现：闲置商品生命周期 + 商品托管订单状态机，一个域一个服务、共用 {@link IIdleRepository}。
 *
 * <p>商品侧：发布即可见（落库 + feed 索引行 + 搜索同步消息同事务），草稿仅本人可见，
 * 上下架 / 到期 / 编辑都经仓储的"带搜索同步"原子用例，退出可见态与索引同步严格同事务。
 *
 * <p>订单侧：5 态订单状态机 + 库存台账扣减/回补（multi-quantity-listing，同域仓储同事务），
 * 全部条件 UPDATE 防并发（防超卖唯一闸门在台账 remaining 的条件扣减上）；跨表原子写
 * （订单 + 台账扣减 + 流水 + 搜索同步 + 超时注册）收成 IIdleRepository 的原子用例，事务在仓储实现内。
 *
 * <p>支付/取消的资金链路为两段式（ADR-0016）：本服务只做判据与状态翻转（翻转段，单仓储事务），
 * 资金段（冻结/解冻）由 case 层按结果对象顺序执行——支付资金段先行（余额不足订单留待支付），
 * 取消翻转段先行（翻转未命中零残留），宕机窗口的长款中间态由 {@code productFundOrphanScan} 收敛。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdleServiceImpl implements IIdleService {

    private static final int SCAN_BATCH_SIZE = 100;

    private static final int PAY_TIMEOUT_MINUTES = 15;

    /** 单笔订单件数上限（同款多件挂单的买入上限，防误操作巨量单） */
    private static final int MAX_ORDER_QUANTITY = 999;

    private final IIdleRepository idleRepository;

    // ==================== 商品生命周期 ====================

    @Override
    public String publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                                 String tradeLocation, String category, Integer quantity) {
        // 信用分门槛与同步初筛由 case 层前置校验
        ProductEntity productEntity = ProductEntity.create(userId, title, description, images, price,
                tradeLocation, ProductCategoryVO.from(category), quantity);
        // 发布即可见：落库 + feed 索引行诞生 + 搜索同步消息同事务（实现内）
        idleRepository.save(productEntity);
        return productEntity.getProductId();
    }

    @Override
    public String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                                   String tradeLocation, String category, Integer quantity) {
        // 草稿为私有停靠内容：不做信用分门槛（提交时才校验），字段可缺省
        ProductCategoryVO categoryVO = category == null ? null : ProductCategoryVO.from(category);
        ProductEntity productEntity = ProductEntity.createDraft(userId, title, description, images, price,
                tradeLocation, categoryVO, quantity);
        idleRepository.save(productEntity);
        return productEntity.getProductId();
    }

    @Override
    public void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                                   BigDecimal price, String tradeLocation, String category, Integer quantity) {
        ProductEntity productEntity = getOwnedProductDraft(userId, productId);
        ProductCategoryVO categoryVO = category == null ? null : ProductCategoryVO.from(category);
        productEntity.applyDraftContent(title, description, images, price, tradeLocation, categoryVO, quantity);
        idleRepository.updateDraft(productEntity);
    }

    @Override
    public ProductEntity getOwnedProductDraft(String userId, String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "草稿不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        if (productEntity.getStatus() != ProductStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可编辑");
        }
        return productEntity;
    }

    @Override
    public void submitProductDraft(String userId, String productId) {
        // 完整性校验由 case 层前置
        getOwnedProductDraft(userId, productId);
        if (!idleRepository.submitDraft(productId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
    }

    @Override
    public void takeDownProduct(String userId, String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        if (productEntity.getStatus() != ProductStatusVO.ON_SALE) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅在售商品可下架");
        }
        doTakeDownToDraft(productId);
    }

    @Override
    public void expireProductToDraft(String productId) {
        // 幂等：扫描只选 ON_SALE，此处条件 UPDATE 兜底并发（已下架/已成交静默跳过）
        doTakeDownToDraft(productId);
    }

    /** 在售退出可见态共用路径：条件 UPDATE ON_SALE → DRAFT + 同事务搜索同步（消费方删文档） */
    private void doTakeDownToDraft(String productId) {
        idleRepository.takeDownWithSearchSync(productId);
    }

    @Override
    public List<String> listExpiredProductIds() {
        return idleRepository.listExpiredOnSaleIds(LocalDateTime.now(), SCAN_BATCH_SIZE);
    }

    @Override
    public ProductEntity getOwnedOnSaleProduct(String userId, String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 编辑语义守卫：仅 ON_SALE 可编辑
        if (productEntity.getStatus() != ProductStatusVO.ON_SALE) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品不在售，不可编辑");
        }
        return productEntity;
    }

    @Override
    public void editOnSaleProduct(String productId, String title, String description, List<String> images, BigDecimal price) {
        boolean contentChanged = title != null || description != null || images != null;
        if (!contentChanged && price == null) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无变更内容");
        }
        if (contentChanged) {
            ProductEntity editEntity = new ProductEntity();
            editEntity.setProductId(productId);
            editEntity.setTitle(title);
            editEntity.setDescription(description);
            editEntity.setImages(images);
            editEntity.setPrice(price);
            // 改内容立即生效（状态不变），同事务落搜索同步消息（消费方按状态 upsert）
            idleRepository.editOnSaleContentWithSearchSync(editEntity);
            return;
        }
        // 仅改价：立即生效（价格不进搜索文档内容，但价格快照列同事务跟索引）
        if (!idleRepository.updateOnSalePrice(productId, price)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
        }
    }

    @Override
    public ProductFeedPageVO listProducts(String category, BigDecimal minPrice, BigDecimal maxPrice, String sort,
                                          String cursor, int page, int pageSize) {
        ProductCategoryVO categoryVO = toCategory(category);
        String sortType = ProductFeedCursorVO.normalizeSort(sort);
        // 筛选不索引化（索引表无 category / price 区间列，见 map「筛选不索引化」）：带筛选一律走旧直查
        if (categoryVO != null || minPrice != null || maxPrice != null) {
            return legacyPage(categoryVO, minPrice, maxPrice, sortType, page, pageSize);
        }
        // 游标解码在 try 之外：非法游标是调用方错误（PARAM_ERROR），不是索引不可用的降级信号
        ProductFeedCursorVO cursorVO = cursor == null || cursor.isBlank()
                ? ProductFeedCursorVO.firstPage(sortType)
                : ProductFeedCursorVO.decode(cursor, sortType);
        try {
            return pageByFeedIndex(sortType, cursorVO, pageSize);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("商品 feed 索引查询失败，降级 DB 直查：sort={}", sortType, e);
        }
        return legacyPage(categoryVO, minPrice, maxPrice, sortType, page, pageSize);
    }

    /**
     * 索引路径：product_feed_index 按 (排序键, product_id) 索引序扫描出 id（多探一行探下页），
     * 再 {@code listByIds} 批量回查组装、按扫描序重排。下一页游标 = 本页最后一行的排序键锚点，
     * 只在确认有下一页（探到多一行）时发放；扫描为空 = 游标已越过末尾，返回空页（不发游标）
     */
    private ProductFeedPageVO pageByFeedIndex(String sortType, ProductFeedCursorVO cursorVO, int pageSize) {
        List<ProductFeedIndexItemVO> rows = idleRepository.scanFeedIndex(sortType, cursorVO, pageSize + 1);
        if (rows.isEmpty()) {
            return new ProductFeedPageVO(List.of(), null, true);
        }
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<String> productIds = rows.stream().map(ProductFeedIndexItemVO::getProductId).toList();
        // 浏览量不取索引快照：listByIds 已 JOIN 计数窄表拿到精确 UV（与详情同源），索引列只是排序键
        Map<String, ProductEntity> byId = idleRepository.listByIds(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getProductId, Function.identity(), (a, b) -> a, HashMap::new));
        // 索引行缺 product 行（存量异常，重建 job 修）只跳过不补位：游标锚在扫描序上，补位会引入重复
        List<ProductEntity> products = productIds.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .toList();
        String nextCursor = hasMore
                ? ProductFeedCursorVO.anchorOf(sortType, rows.get(rows.size() - 1)).encode()
                : null;
        return new ProductFeedPageVO(products, nextCursor, true);
    }

    /** 旧直查路径（筛选过滤 / 降级）：offset 分页，精确 COUNT 由调用方另取 */
    private ProductFeedPageVO legacyPage(ProductCategoryVO categoryVO, BigDecimal minPrice, BigDecimal maxPrice,
                                         String sortType, int page, int pageSize) {
        return new ProductFeedPageVO(idleRepository.pageList(categoryVO, minPrice, maxPrice, sortType,
                (page - 1) * pageSize, pageSize), null, false);
    }

    @Override
    public long countProducts(String category, BigDecimal minPrice, BigDecimal maxPrice) {
        return idleRepository.count(toCategory(category), minPrice, maxPrice);
    }

    @Override
    public void rebuildFeedIndex() {
        idleRepository.rebuildFeedIndex();
    }

    @Override
    public List<String> listViewedProductIds(String userId, List<String> productIds) {
        return idleRepository.selectViewedProductIds(userId, productIds);
    }

    @Override
    public ProductEntity productDetail(String productId, String currentUserId) {
        ProductEntity productEntity = getVisibleProduct(productId, currentUserId);
        // 浏览计数（UV 口径）：fire-and-forget，入队即返回（窗口过后一组一个事务落明细 + 加速列）。
        // 匿名不计入（仓储层拦下——商品语义变更，旧口径匿名也累加 PV）；允许丢、不影响本次请求结果，
        // 详情页读到的仍是库里的当前值
        idleRepository.recordProductView(productId, currentUserId);
        if (currentUserId != null) {
            productEntity.setFavorited(idleRepository.isFavorited(productId, currentUserId));
        }
        productEntity.setFavoriteCount(idleRepository.countFavorites(productId));
        return productEntity;
    }

    @Override
    public void deleteProduct(String productId, String currentUserId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null
                || (productEntity.getStatus() != ProductStatusVO.ON_SALE
                && productEntity.getStatus() != ProductStatusVO.DRAFT)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在或已下架");
        }
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        boolean publiclyVisible = productEntity.getStatus() == ProductStatusVO.ON_SALE;
        // 在售商品对外可见过，软删后同事务落搜索同步消息；草稿从未对外可见，无需同步
        idleRepository.deleteWithSearchSync(productId, publiclyVisible);
    }

    @Override
    public void markSold(String productId, String currentUserId) {
        ProductEntity productEntity = getOnSaleProduct(productId);
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 售出后商品退出搜索结果，同事务落搜索同步消息（消费方删除 ES 文档）
        idleRepository.markSoldWithSearchSync(productId);
    }

    @Override
    public void relistProduct(String productId, String currentUserId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() == ProductStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 仅 SOLD 可重新上架（multi-quantity-listing：真卖完 remaining = 0 不可重上架）
        if (productEntity.getStatus() != ProductStatusVO.SOLD) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅已售出的商品可重新上架");
        }
        if (!idleRepository.relist(productId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品已无库存，无法重新上架");
        }
    }

    @Override
    public void restockProduct(String userId, String productId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "加货件数至少 1");
        }
        getOwnedOnSaleProduct(userId, productId);
        idleRepository.restock(productId, quantity, userId);
    }

    @Override
    public void favoriteProduct(String productId, String userId) {
        getOnSaleProduct(productId);
        if (!idleRepository.addFavorite(productId, userId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "请勿重复收藏");
        }
    }

    @Override
    public void unfavoriteProduct(String productId, String userId) {
        getOnSaleProduct(productId);
        if (!idleRepository.removeFavorite(productId, userId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "尚未收藏");
        }
    }

    @Override
    public List<ProductEntity> listFavorites(String userId, int page, int pageSize) {
        List<ProductEntity> productList = idleRepository.listFavorites(userId, (page - 1) * pageSize, pageSize);
        productList.forEach(product -> product.setFavorited(true));
        return productList;
    }

    @Override
    public List<ProductEntity> listMyProducts(String userId, int page, int pageSize) {
        return idleRepository.pageBySeller(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countMyProducts(String userId) {
        return idleRepository.countBySeller(userId);
    }

    @Override
    public List<ProductEntity> listUserOnSaleProducts(String userId, int page, int pageSize) {
        return idleRepository.pageOnSaleByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countUserOnSaleProducts(String userId) {
        return idleRepository.countOnSaleByUser(userId);
    }

    private ProductEntity getOnSaleProduct(String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() != ProductStatusVO.ON_SALE) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在或已下架");
        }
        return productEntity;
    }

    /**
     * 详情/浏览可见性：ON_SALE/SOLD 对外可见（已售可回看）；LOCKED 为历史遗留态（订单流已停写，
     * 仅存量行可见，不外露也不挡回看）；DRAFT 仅卖家本人可见（草稿不外露）；软删对所有人不可见
     */
    private ProductEntity getVisibleProduct(String productId, String currentUserId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() == ProductStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在或已删除");
        }
        if (!isPubliclyVisible(productEntity.getStatus()) && !productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在或已删除");
        }
        return productEntity;
    }

    private boolean isPubliclyVisible(ProductStatusVO status) {
        return status == ProductStatusVO.ON_SALE || status == ProductStatusVO.LOCKED || status == ProductStatusVO.SOLD;
    }

    private ProductCategoryVO toCategory(String category) {
        return category == null || category.isBlank() ? null : ProductCategoryVO.from(category);
    }

    // ==================== 商品托管订单 ====================

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
        return idleRepository.createOrderWithInventory(orderEntity, productId, PAY_TIMEOUT_MINUTES);
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
        if (!idleRepository.payOrderById(orderId, buyerId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
    }

    @Override
    public void markDelivered(String orderId, String sellerId) {
        ProductOrderEntity orderEntity = getOrder(orderId);
        if (!orderEntity.isSeller(sellerId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有卖家可以标记交付");
        }
        if (!idleRepository.markDeliveredOrderById(orderId, sellerId)) {
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
        if (!idleRepository.confirmOrderById(orderId, buyerId)) {
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
        // 翻转段先行（两段式 ADR-0016）：取消翻转（含台账回补 + 搜索同步）
        // 单仓储事务，未命中抛异常——资金段尚未执行，零残留；翻转与 case 层解冻之间的宕机孤儿
        // （订单已取消但货款悬置冻结，长款中间态）由 productFundOrphanScan 扫描收敛
        if (!idleRepository.cancelOrderWithSearchSync(orderId, buyerId)) {
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
            return idleRepository.listMineByBuyer(userId, offset, pageSize);
        }
        if ("sell".equals(role)) {
            return idleRepository.listMineBySeller(userId, offset, pageSize);
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "role 不合法（buy/sell）");
    }

    @Override
    public long countMineOrders(String role, String userId) {
        if ("buy".equals(role)) {
            return idleRepository.countMineByBuyer(userId);
        }
        if ("sell".equals(role)) {
            return idleRepository.countMineBySeller(userId);
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "role 不合法（buy/sell）");
    }

    @Override
    public void handlePayTimeout(String orderId) {
        ProductOrderEntity orderEntity = idleRepository.findOrderById(orderId);
        if (orderEntity == null || orderEntity.getStatus() != ProductOrderStatusVO.PENDING_PAYMENT) {
            // 已支付/已取消：幂等忽略
            return;
        }
        // 域内事务（仓储内）：订单取消 + 商品释放 + 搜索同步
        idleRepository.cancelPendingPaymentWithSearchSync(orderId);
        log.info("[ProductPayTimeout] 待支付订单超时取消 orderId={}", orderId);
    }

    /** 订单总金额 = 成交单价快照 × 成交件数（钱包冻结/解冻/入账统一按总额走） */
    private static BigDecimal totalAmount(ProductOrderEntity orderEntity) {
        int quantity = orderEntity.getQuantity() == null ? 1 : orderEntity.getQuantity();
        return orderEntity.getPrice().multiply(BigDecimal.valueOf(quantity));
    }

    private ProductEntity getAvailableProduct(String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() != ProductStatusVO.ON_SALE) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在或已售出");
        }
        return productEntity;
    }

    private ProductOrderEntity getOrder(String orderId) {
        ProductOrderEntity orderEntity = idleRepository.findOrderById(orderId);
        if (orderEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "订单不存在");
        }
        return orderEntity;
    }
}
