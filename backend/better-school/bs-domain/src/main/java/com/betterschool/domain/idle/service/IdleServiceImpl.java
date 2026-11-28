package com.betterschool.domain.idle.service;

import com.betterschool.domain.idle.adapter.repository.IIdleRepository;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductCancelResult;
import com.betterschool.domain.idle.model.valobj.ProductCategoryEnum;
import com.betterschool.domain.idle.model.valobj.ProductConfirmResult;
import com.betterschool.domain.idle.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.idle.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.domain.idle.model.valobj.ProductFeedPageVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderEventVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusEnum;
import com.betterschool.domain.idle.model.valobj.ProductStatusEnum;
import com.betterschool.types.common.SearchPageVO;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                tradeLocation, ProductCategoryEnum.from(category), quantity);
        // 发布即可见：落库 + feed 索引行诞生 + 搜索同步消息同事务（实现内）
        idleRepository.save(productEntity);
        return productEntity.getProductId();
    }

    @Override
    public String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                                   String tradeLocation, String category, Integer quantity) {
        // 草稿为私有停靠内容：不做信用分门槛（提交时才校验），字段可缺省
        ProductCategoryEnum productCategoryVO = category == null ? null : ProductCategoryEnum.from(category);
        ProductEntity productEntity = ProductEntity.createDraft(userId, title, description, images, price,
                tradeLocation, productCategoryVO, quantity);
        idleRepository.save(productEntity);
        return productEntity.getProductId();
    }

    @Override
    public void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                                   BigDecimal price, String tradeLocation, String category, Integer quantity) {
        ProductEntity productEntity = getOwnedProductDraft(userId, productId);
        ProductCategoryEnum productCategoryVO = category == null ? null : ProductCategoryEnum.from(category);
        productEntity.applyDraftContent(title, description, images, price, tradeLocation, productCategoryVO, quantity);
        idleRepository.updateDraft(productEntity);
    }

    @Override
    public ProductEntity getOwnedProductDraft(String userId, String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "草稿不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能操作自己发布的商品");
        }
        if (productEntity.getStatus() != ProductStatusEnum.DRAFT) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "仅草稿可编辑");
        }
        return productEntity;
    }

    @Override
    public void submitProductDraft(String userId, String productId) {
        // 归属/状态守卫 + 提交不变式都在域内裁决（与 post 提交同口径）；信用分门槛由 case 层前置
        ProductEntity productEntity = getOwnedProductDraft(userId, productId);
        ensureDraftComplete(productEntity);
        if (!idleRepository.submitDraft(productId)) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
    }

    /**
     * 提交不变式：标题/价格/交易地点/分类必填（草稿允许缺省停靠，提交转上架时必须校验）。
     * 条件 UPDATE 只按 state = 'DRAFT' 兜底并发竞态，内容完整性由本裁决负责
     */
    private void ensureDraftComplete(ProductEntity productEntity) {
        if (isBlank(productEntity.getTitle()) || productEntity.getPrice() == null
                || isBlank(productEntity.getTradeLocation()) || productEntity.getCategory() == null) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "草稿内容不完整，请补全标题、价格、交易地点与分类后再提交");
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    @Override
    public void takeDownProduct(String userId, String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能操作自己发布的商品");
        }
        if (productEntity.getStatus() != ProductStatusEnum.ON_SALE) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "仅在售商品可下架");
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
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 编辑语义守卫：仅 ON_SALE 可编辑
        if (productEntity.getStatus() != ProductStatusEnum.ON_SALE) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "商品不在售，不可编辑");
        }
        return productEntity;
    }

    @Override
    public void editOnSaleProduct(String userId, String productId, String title, String description, List<String> images,
                                  BigDecimal price) {
        // 归属与在售守卫在此裁决：仓储侧条件 UPDATE 只按 state 兜底，不覆盖归属
        getOwnedOnSaleProduct(userId, productId);
        boolean contentChanged = title != null || description != null || images != null;
        if (!contentChanged && price == null) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "无变更内容");
        }
        if (contentChanged) {
            ProductEntity productEntity = new ProductEntity();
            productEntity.setProductId(productId);
            productEntity.setTitle(title);
            productEntity.setDescription(description);
            productEntity.setImages(images);
            productEntity.setPrice(price);
            // 改内容立即生效（状态不变），同事务落搜索同步消息（消费方按状态 upsert）
            idleRepository.editOnSaleContentWithSearchSync(productEntity);
            return;
        }
        // 仅改价：立即生效（价格不进搜索文档内容，但价格快照列同事务跟索引）
        if (!idleRepository.updateOnSalePrice(productId, price)) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
        }
    }

    @Override
    public ProductFeedPageVO listProducts(String category, BigDecimal minPrice, BigDecimal maxPrice, String sort,
                                          String cursor, int page, int pageSize) {
        ProductCategoryEnum productCategoryVO = toCategory(category);
        String sortType = ProductFeedCursorVO.normalizeSort(sort);
        // 筛选不索引化（索引表无 category / price 区间列，见 map「筛选不索引化」）：带筛选一律走旧直查
        if (productCategoryVO != null || minPrice != null || maxPrice != null) {
            return legacyPage(productCategoryVO, minPrice, maxPrice, sortType, page, pageSize);
        }
        // 游标解码在 try 之外：非法游标是调用方错误（PARAM_ERROR），不是索引不可用的降级信号
        ProductFeedCursorVO productFeedCursorVO = cursor == null || cursor.isBlank()
                ? ProductFeedCursorVO.firstPage(sortType)
                : ProductFeedCursorVO.decode(cursor, sortType);
        try {
            return pageByFeedIndex(sortType, productFeedCursorVO, pageSize);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("商品 feed 索引查询失败，降级 DB 直查：sort={}", sortType, e);
        }
        return legacyPage(productCategoryVO, minPrice, maxPrice, sortType, page, pageSize);
    }

    /**
     * 索引路径：product_feed_index 按 (排序键, product_id) 索引序扫描出 id（多探一行探下页），
     * 再 {@code listByIds} 批量回查组装、按扫描序重排。下一页游标 = 本页最后一行的排序键锚点，
     * 只在确认有下一页（探到多一行）时发放；扫描为空 = 游标已越过末尾，返回空页（不发游标）
     */
    private ProductFeedPageVO pageByFeedIndex(String sortType, ProductFeedCursorVO productFeedCursorVO, int pageSize) {
        List<ProductFeedIndexItemVO> rows = idleRepository.scanFeedIndex(sortType, productFeedCursorVO, pageSize + 1);
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
    private ProductFeedPageVO legacyPage(ProductCategoryEnum productCategoryVO, BigDecimal minPrice, BigDecimal maxPrice,
                                         String sortType, int page, int pageSize) {
        return new ProductFeedPageVO(idleRepository.pageList(productCategoryVO, minPrice, maxPrice, sortType,
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
    public ProductEntity getProductEntity(String productId) {
        return idleRepository.findById(productId);
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
                || (productEntity.getStatus() != ProductStatusEnum.ON_SALE
                && productEntity.getStatus() != ProductStatusEnum.DRAFT)) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在或已下架");
        }
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能操作自己发布的商品");
        }
        boolean publiclyVisible = productEntity.getStatus() == ProductStatusEnum.ON_SALE;
        // 在售商品对外可见过，软删后同事务落搜索同步消息；草稿从未对外可见，无需同步
        idleRepository.deleteWithSearchSync(productId, publiclyVisible);
    }

    @Override
    public void markSold(String productId, String currentUserId) {
        ProductEntity productEntity = getOnSaleProduct(productId);
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 售出后商品退出搜索结果，同事务落搜索同步消息（消费方删除 ES 文档）
        idleRepository.markSoldWithSearchSync(productId);
    }

    @Override
    public void relistProduct(String productId, String currentUserId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() == ProductStatusEnum.DELETED) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 仅 SOLD 可重新上架（multi-quantity-listing：真卖完 remaining = 0 不可重上架）
        if (productEntity.getStatus() != ProductStatusEnum.SOLD) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "仅已售出的商品可重新上架");
        }
        if (!idleRepository.relist(productId)) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "商品已无库存，无法重新上架");
        }
    }

    @Override
    public void restockProduct(String userId, String productId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "加货件数至少 1");
        }
        getOwnedOnSaleProduct(userId, productId);
        idleRepository.restock(productId, quantity, userId);
    }

    @Override
    public void favoriteProduct(String productId, String userId) {
        getOnSaleProduct(productId);
        if (!idleRepository.addFavorite(productId, userId)) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "请勿重复收藏");
        }
    }

    @Override
    public void unfavoriteProduct(String productId, String userId) {
        getOnSaleProduct(productId);
        if (!idleRepository.removeFavorite(productId, userId)) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "尚未收藏");
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
        if (productEntity == null || productEntity.getStatus() != ProductStatusEnum.ON_SALE) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在或已下架");
        }
        return productEntity;
    }

    /**
     * 详情/浏览可见性：ON_SALE/SOLD 对外可见（已售可回看）；LOCKED 为历史遗留态（订单流已停写，
     * 仅存量行可见，不外露也不挡回看）；DRAFT 仅卖家本人可见（草稿不外露）；软删对所有人不可见
     */
    private ProductEntity getVisibleProduct(String productId, String currentUserId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() == ProductStatusEnum.DELETED) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在或已删除");
        }
        if (!isPubliclyVisible(productEntity.getStatus()) && !productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在或已删除");
        }
        return productEntity;
    }

    private boolean isPubliclyVisible(ProductStatusEnum productStatusVO) {
        return productStatusVO == ProductStatusEnum.ON_SALE || productStatusVO == ProductStatusEnum.LOCKED || productStatusVO == ProductStatusEnum.SOLD;
    }

    @Override
    public SearchPageVO<ProductEntity> searchProducts(String keyword, int page, int pageSize) {
        // 索引只负责命中与排序：命中 id 回库批量反查，按 id 顺序重排（IN 查询不保序），
        // 命中后状态已变化的实体在这里被过滤掉——本域才是「在售」的最终判据
        SearchPageVO<String> idPage = idleRepository.searchProductIds(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<String, ProductEntity> byId = idleRepository.listByIds(idPage.getItems()).stream()
                .filter(product -> product.getStatus() == ProductStatusEnum.ON_SALE)
                .collect(Collectors.toMap(ProductEntity::getProductId, Function.identity()));
        return new SearchPageVO<>(idPage.getTotal(), idPage.getItems().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList());
    }

    @Override
    public void syncSearchDoc(String productId) {
        idleRepository.syncProductSearchDoc(productId);
    }

    @Override
    public int rebuildSearchIndex() {
        return idleRepository.rebuildProductSearchIndex();
    }

    private ProductCategoryEnum toCategory(String category) {
        return category == null || category.isBlank() ? null : ProductCategoryEnum.from(category);
    }

    // ==================== 商品托管订单 ====================

    @Override
    public String createOrder(String buyerId, String productId, int quantity) {
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "购买件数须在 1.." + MAX_ORDER_QUANTITY + " 之间");
        }
        ProductEntity productEntity = getAvailableProduct(productId);
        if (productEntity.isOwner(buyerId)) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "不能购买自己发布的商品");
        }
        ProductOrderEntity productOrderEntity = ProductOrderEntity.create(buyerId, productEntity, quantity);
        // 域内事务（仓储内）：订单 + 台账扣减 + 流水 + 售罄翻态 + 超时注册原子
        return idleRepository.createOrderWithInventory(productOrderEntity, productId, PAY_TIMEOUT_MINUTES);
    }

    @Override
    public ProductOrderEntity preparePayOrder(String orderId, String buyerId) {
        ProductOrderEntity productOrderEntity = idleRepository.findOrderById(orderId);
        if (productOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在");
        }
        if (!productOrderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能支付自己的订单");
        }
        // 可支付态：待支付，或已支付及其后态（重试场景，confirm 按幂等回放跳过翻转）
        ProductOrderStatusEnum status = productOrderEntity.getStatus();
        if (status != ProductOrderStatusEnum.PENDING_PAYMENT && status != ProductOrderStatusEnum.PAID
                && status != ProductOrderStatusEnum.DELIVERED && status != ProductOrderStatusEnum.COMPLETED) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "订单当前状态不可支付");
        }
        // 支付只翻订单状态：库存已在下单时扣减（售罄已随行翻 SOLD），无商品状态联动与搜索同步
        return productOrderEntity;
    }

    @Override
    public void confirmPayOrder(String orderId, String buyerId) {
        // 两段式 ADR-0016 翻转段（资金段冻结已由钱包支付事务先行完成，PRODUCT_FROZEN 流水 uk 幂等）：
        // 「待支付 → 已支付」条件翻转（守卫买家 + 前态，PAID 事件随行落表，单仓储事务）。
        // 未命中多为重试已翻转成功 → 回读按已支付及其后态幂等返回；其余状态说明发生并发变化，
        // 此刻货款可能已冻结（订单留待支付的长款中间态），孤儿由 productFundOrphanScan 扫描解冻收敛
        if (idleRepository.payOrderById(orderId, buyerId)) {
            return;
        }
        ProductOrderEntity productOrderEntity = idleRepository.findOrderById(orderId);
        if (productOrderEntity != null && (productOrderEntity.getStatus() == ProductOrderStatusEnum.PAID
                || productOrderEntity.getStatus() == ProductOrderStatusEnum.DELIVERED
                || productOrderEntity.getStatus() == ProductOrderStatusEnum.COMPLETED)) {
            return; // 幂等：上次支付已提交成功（重试场景）
        }
        throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
    }

    @Override
    public void markDelivered(String orderId, String sellerId) {
        ProductOrderEntity productOrderEntity = idleRepository.findOrderById(orderId);
        if (productOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在");
        }
        if (!productOrderEntity.isSeller(sellerId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只有卖家可以标记交付");
        }
        if (!idleRepository.markDeliveredOrderById(orderId, sellerId)) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
    }

    @Override
    public ProductConfirmResult confirmReceived(String orderId, String buyerId) {
        ProductOrderEntity productOrderEntity = idleRepository.findOrderById(orderId);
        if (productOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在");
        }
        if (!productOrderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只有买家可以确认收货");
        }
        // 幂等判定：已完成视为上次确认已提交成功（重试场景），跳过直接返回
        if (productOrderEntity.getStatus() == ProductOrderStatusEnum.COMPLETED) {
            return new ProductConfirmResult(productOrderEntity.getBuyerId(), productOrderEntity.getSellerId(), productOrderEntity.totalAmount());
        }
        if (productOrderEntity.getStatus() != ProductOrderStatusEnum.DELIVERED) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在或未交付");
        }
        // 确认收货只翻订单状态：库存已扣、售罄已翻态，无商品状态联动与搜索同步
        if (!idleRepository.confirmOrderById(orderId, buyerId)) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        return new ProductConfirmResult(productOrderEntity.getBuyerId(), productOrderEntity.getSellerId(), productOrderEntity.totalAmount());
    }

    @Override
    public ProductCancelResult cancelOrder(String orderId, String buyerId) {
        ProductOrderEntity productOrderEntity = idleRepository.findOrderById(orderId);
        if (productOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在");
        }
        if (!productOrderEntity.isBuyer(buyerId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只有买家可以取消订单");
        }
        // 幂等判定（重试场景）：已取消/已完成视为本次取消已提交成功（终态短路，零副作用；
        // 资金段的补齐由 productFundOrphanScan 扫描兜底）
        if (productOrderEntity.getStatus() == ProductOrderStatusEnum.CANCELLED
                || productOrderEntity.getStatus() == ProductOrderStatusEnum.COMPLETED) {
            return new ProductCancelResult(false, productOrderEntity.getBuyerId(), productOrderEntity.totalAmount());
        }
        if (productOrderEntity.getStatus() == ProductOrderStatusEnum.DELIVERED) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "已交付后不可取消，请确认收货");
        }
        // 待支付未冻结无需退款；已支付需解冻退回
        boolean needRefund = productOrderEntity.getStatus() == ProductOrderStatusEnum.PAID;
        // 翻转段先行（两段式 ADR-0016）：取消翻转（含台账回补 + 搜索同步）
        // 单仓储事务，未命中抛异常——资金段尚未执行，零残留；翻转与 case 层解冻之间的宕机孤儿
        // （订单已取消但货款悬置冻结，长款中间态）由 productFundOrphanScan 扫描收敛
        if (!idleRepository.cancelOrderWithSearchSync(orderId, buyerId)) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "订单状态已变化，请刷新重试");
        }
        return new ProductCancelResult(needRefund, productOrderEntity.getBuyerId(), productOrderEntity.totalAmount());
    }

    @Override
    public ProductOrderEntity orderDetail(String orderId, String currentUserId) {
        ProductOrderEntity productOrderEntity = idleRepository.findOrderById(orderId);
        if (productOrderEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "订单不存在");
        }
        // 仅交易双方（含卖家/买家）可见
        if (!productOrderEntity.isBuyer(currentUserId) && !productOrderEntity.isSeller(currentUserId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只有交易双方可以查看订单");
        }
        return productOrderEntity;
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
        throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "role 不合法（buy/sell）");
    }

    @Override
    public long countMineOrders(String role, String userId) {
        if ("buy".equals(role)) {
            return idleRepository.countMineByBuyer(userId);
        }
        if ("sell".equals(role)) {
            return idleRepository.countMineBySeller(userId);
        }
        throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "role 不合法（buy/sell）");
    }

    @Override
    public ProductOrderEntity getOrderEntity(String orderId) {
        return idleRepository.findOrderById(orderId);
    }

    @Override
    public List<ProductOrderEntity> listRecentSettled(int windowMinutes, int limit) {
        return idleRepository.listRecentSettled(windowMinutes, limit);
    }

    @Override
    public ProductOrderEventVO latestEventOf(String orderId) {
        return idleRepository.latestEventOf(orderId);
    }

    @Override
    public void handlePayTimeout(String orderId) {
        ProductOrderEntity productOrderEntity = idleRepository.findOrderById(orderId);
        if (productOrderEntity == null || productOrderEntity.getStatus() != ProductOrderStatusEnum.PENDING_PAYMENT) {
            // 已支付/已取消：幂等忽略
            return;
        }
        // 域内事务（仓储内）：订单取消 + 商品释放 + 搜索同步
        idleRepository.cancelPendingPaymentWithSearchSync(orderId);
        log.info("[ProductPayTimeout] 待支付订单超时取消 orderId={}", orderId);
    }

    private ProductEntity getAvailableProduct(String productId) {
        ProductEntity productEntity = idleRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() != ProductStatusEnum.ON_SALE) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "商品不存在或已售出");
        }
        return productEntity;
    }
}
