package com.betterschool.domain.trade.service.product;

import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.valobj.ProductCategoryVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedPageVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements IProductService {

    private static final int SCAN_BATCH_SIZE = 100;
    private static final int REVIEW_REMINDER_HOURS = 24;

    private final ITradeRepository tradeRepository;

    @Override
    public String publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                               String tradeLocation, String category) {
        // 信用分门槛与同步初筛由 case 层前置校验
        ProductEntity productEntity = ProductEntity.create(userId, title, description, images, price,
                tradeLocation, ProductCategoryVO.from(category));
        // 先审后售：进入待审，审核通过转 ON_SALE 时才落搜索同步消息
        tradeRepository.save(productEntity);
        return productEntity.getProductId();
    }

    @Override
    public String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                                 String tradeLocation, String category) {
        // 草稿为私有停靠内容：不做初筛、不做信用分门槛（提交时才校验），字段可缺省
        ProductCategoryVO categoryVO = category == null ? null : ProductCategoryVO.from(category);
        ProductEntity productEntity = ProductEntity.createDraft(userId, title, description, images, price,
                tradeLocation, categoryVO);
        tradeRepository.save(productEntity);
        return productEntity.getProductId();
    }

    @Override
    public void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                                   BigDecimal price, String tradeLocation, String category) {
        ProductEntity productEntity = getOwnedProductDraft(userId, productId);
        ProductCategoryVO categoryVO = category == null ? null : ProductCategoryVO.from(category);
        productEntity.applyDraftContent(title, description, images, price, tradeLocation, categoryVO);
        tradeRepository.updateDraft(productEntity);
    }

    @Override
    public ProductEntity getOwnedProductDraft(String userId, String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "草稿不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        if (productEntity.getStatus() != ProductStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可编辑，待审中的商品可先撤回再修改");
        }
        return productEntity;
    }

    @Override
    public void submitProductDraft(String userId, String productId) {
        // 完整性校验与同步初筛由 case 层前置
        getOwnedProductDraft(userId, productId);
        if (!tradeRepository.submitDraft(productId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
    }

    @Override
    public void withdrawProduct(String userId, String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        if (productEntity.getStatus() != ProductStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅待审中的商品可撤回");
        }
        if (!tradeRepository.withdraw(productId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
        }
    }

    @Override
    public void takeDownProduct(String userId, String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
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
        tradeRepository.takeDownWithSearchSync(productId);
    }

    @Override
    public List<String> listExpiredProductIds() {
        return tradeRepository.listExpiredOnSaleIds(LocalDateTime.now(), SCAN_BATCH_SIZE);
    }

    @Override
    public List<String> listReviewTimeoutProductIds() {
        // PENDING_REVIEW 由提交/编辑条件 UPDATE 翻转而来，update_time 即进入待审时间
        LocalDateTime cutoff = LocalDateTime.now().minusHours(REVIEW_REMINDER_HOURS);
        return tradeRepository.listReviewTimeoutIds(cutoff, SCAN_BATCH_SIZE);
    }

    @Override
    public ProductEntity getOwnedOnSaleProduct(String userId, String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 编辑语义守卫：待审中只能撤回转草稿；被拒走草稿箱自救路径；其余非在售状态不可编辑
        if (productEntity.getStatus() == ProductStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "待审中不可编辑，可先撤回转草稿后修改");
        }
        if (productEntity.getStatus() == ProductStatusVO.REJECTED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品已被拒绝，可转入草稿箱修改后重新提交");
        }
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
            // 改内容回待审：期间他人不可见（列表 SQL 锁 ON_SALE）、卖家本人可见；同事务落搜索同步消息，消费方按状态删文档
            tradeRepository.editOnSaleContentWithSearchSync(editEntity);
            return;
        }
        // 仅改价：立即生效，状态不变、不进审核、无通知（价格不进搜索文档，无需索引同步）
        if (!tradeRepository.updateOnSalePrice(productId, price)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
        }
    }

    @Override
    public void moveRejectedProductToDraft(String userId, String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        if (productEntity.getStatus() != ProductStatusVO.REJECTED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅被拒绝的商品可转入草稿箱");
        }
        if (!tradeRepository.rejectToDraft(productId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
        }
    }

    @Override
    public void reviewApproveProduct(String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        // 幂等：已通过在售（重试场景）
        if (productEntity.getStatus() == ProductStatusVO.ON_SALE) {
            return;
        }
        if (productEntity.getStatus() != ProductStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品已被处理，无法通过审核");
        }
        // 先审后售：审核通过转 ON_SALE 时落搜索同步消息（进入对外可见态，消费方按状态 upsert）
        tradeRepository.reviewApproveWithSearchSync(productId);
    }

    @Override
    public void reviewRejectProduct(String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        // 幂等：已拒绝（重试场景）
        if (productEntity.getStatus() == ProductStatusVO.REJECTED) {
            return;
        }
        if (productEntity.getStatus() != ProductStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品已被处理，无法拒绝");
        }
        if (!tradeRepository.reviewRejectById(productId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新重试");
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
        List<ProductFeedIndexItemVO> rows = tradeRepository.scanFeedIndex(sortType, cursorVO, pageSize + 1);
        if (rows.isEmpty()) {
            return new ProductFeedPageVO(List.of(), null, true);
        }
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<String> productIds = rows.stream().map(ProductFeedIndexItemVO::getProductId).toList();
        // 浏览量不取索引快照：listByIds 已 JOIN 计数窄表拿到精确 UV（与详情同源），索引列只是排序键
        Map<String, ProductEntity> byId = tradeRepository.listByIds(productIds).stream()
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
        return new ProductFeedPageVO(tradeRepository.pageList(categoryVO, minPrice, maxPrice, sortType,
                (page - 1) * pageSize, pageSize), null, false);
    }

    @Override
    public long countProducts(String category, BigDecimal minPrice, BigDecimal maxPrice) {
        return tradeRepository.count(toCategory(category), minPrice, maxPrice);
    }

    @Override
    public void rebuildFeedIndex() {
        tradeRepository.rebuildFeedIndex();
    }

    @Override
    public List<String> listViewedProductIds(String userId, List<String> productIds) {
        return tradeRepository.selectViewedProductIds(userId, productIds);
    }

    @Override
    public ProductEntity productDetail(String productId, String currentUserId) {
        ProductEntity productEntity = getVisibleProduct(productId, currentUserId);
        // 浏览计数（UV 口径）：fire-and-forget，入队即返回（窗口过后一组一个事务落明细 + 加速列）。
        // 匿名不计入（仓储层拦下——商品语义变更，旧口径匿名也累加 PV）；允许丢、不影响本次请求结果，
        // 详情页读到的仍是库里的当前值
        tradeRepository.recordProductView(productId, currentUserId);
        if (currentUserId != null) {
            productEntity.setFavorited(tradeRepository.isFavorited(productId, currentUserId));
        }
        productEntity.setFavoriteCount(tradeRepository.countFavorites(productId));
        return productEntity;
    }

    @Override
    public void deleteProduct(String productId, String currentUserId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
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
        tradeRepository.deleteWithSearchSync(productId, publiclyVisible);
    }

    @Override
    public void markSold(String productId, String currentUserId) {
        ProductEntity productEntity = getOnSaleProduct(productId);
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 售出后商品退出搜索结果，同事务落搜索同步消息（消费方删除 ES 文档）
        tradeRepository.markSoldWithSearchSync(productId);
    }

    @Override
    public void relistProduct(String productId, String currentUserId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() == ProductStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在");
        }
        if (!productEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的商品");
        }
        // 仅 SOLD 可重新上架：LOCKED（交易中）不可重上架，避免与托管订单互斥
        if (productEntity.getStatus() != ProductStatusVO.SOLD) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅已售出的商品可重新上架");
        }
        // 先审后售：重新上架回到待审，审核通过转 ON_SALE 时才落搜索同步消息
        tradeRepository.relist(productId);
    }

    @Override
    public void favoriteProduct(String productId, String userId) {
        getOnSaleProduct(productId);
        if (!tradeRepository.addFavorite(productId, userId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "请勿重复收藏");
        }
    }

    @Override
    public void unfavoriteProduct(String productId, String userId) {
        getOnSaleProduct(productId);
        if (!tradeRepository.removeFavorite(productId, userId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "尚未收藏");
        }
    }

    @Override
    public List<ProductEntity> listFavorites(String userId, int page, int pageSize) {
        List<ProductEntity> productList = tradeRepository.listFavorites(userId, (page - 1) * pageSize, pageSize);
        productList.forEach(product -> product.setFavorited(true));
        return productList;
    }

    @Override
    public List<ProductEntity> listMyProducts(String userId, int page, int pageSize) {
        return tradeRepository.pageBySeller(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countMyProducts(String userId) {
        return tradeRepository.countBySeller(userId);
    }

    @Override
    public List<ProductEntity> listUserOnSaleProducts(String userId, int page, int pageSize) {
        return tradeRepository.pageOnSaleByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countUserOnSaleProducts(String userId) {
        return tradeRepository.countOnSaleByUser(userId);
    }

    private ProductEntity getOnSaleProduct(String productId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
        if (productEntity == null || productEntity.getStatus() != ProductStatusVO.ON_SALE) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "商品不存在或已下架");
        }
        return productEntity;
    }

    /**
     * 详情/浏览可见性：ON_SALE/LOCKED/SOLD 对外可见（交易中与已售对双方可回看）；
     * DRAFT/PENDING_REVIEW/REJECTED 仅卖家本人可见（待审/草稿/被拒不外露）；软删对所有人不可见
     */
    private ProductEntity getVisibleProduct(String productId, String currentUserId) {
        ProductEntity productEntity = tradeRepository.findById(productId);
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
}
