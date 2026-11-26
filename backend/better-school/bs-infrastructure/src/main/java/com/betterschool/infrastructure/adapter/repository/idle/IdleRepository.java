package com.betterschool.infrastructure.adapter.repository.idle;

import com.betterschool.infrastructure.timeout.TimeoutTaskEntity;
import com.betterschool.infrastructure.timeout.TimeoutTaskStore;
import com.betterschool.domain.idle.adapter.repository.IIdleRepository;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductCategoryVO;
import com.betterschool.domain.idle.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.idle.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderEventVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusVO;
import com.betterschool.domain.idle.model.valobj.ProductStatusVO;
import com.betterschool.infrastructure.adapter.repository.CountAction;
import com.betterschool.infrastructure.adapter.repository.CountEvent;
import com.betterschool.infrastructure.adapter.repository.LikeState;
import com.betterschool.infrastructure.merge.MergeConfig;
import com.betterschool.infrastructure.merge.MergeEngine;
import com.betterschool.infrastructure.merge.MergeResult;
import com.betterschool.infrastructure.merge.MergeStrategy;
import com.betterschool.infrastructure.dao.product.IProductDao;
import com.betterschool.infrastructure.dao.product.po.Product;
import com.betterschool.infrastructure.dao.productorder.IProductOrderDao;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrderEvent;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrder;
import com.betterschool.infrastructure.dao.product.IProductDao;
import com.betterschool.infrastructure.dao.product.po.ProductFeedIndexRow;
import com.betterschool.infrastructure.dao.product.po.Product;
import com.betterschool.infrastructure.dao.productorder.IProductOrderDao;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrderEvent;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrder;
import com.betterschool.infrastructure.dao.product.IProductFavoriteDao;
import com.betterschool.infrastructure.dao.product.IProductFeedIndexDao;
import com.betterschool.infrastructure.dao.product.IProductViewCountDao;
import com.betterschool.infrastructure.dao.product.IProductViewHistoryDao;
import com.betterschool.infrastructure.dao.productinventory.IProductInventoryDao;
import com.betterschool.infrastructure.dao.productinventory.IProductStockEventDao;
import com.betterschool.infrastructure.dao.productinventory.po.ProductInventory;
import com.betterschool.infrastructure.dao.productinventory.po.ProductStockEvent;
import com.betterschool.infrastructure.dao.productorder.IProductOrderEventDao;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.User;
import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.betterschool.infrastructure.search.ISearchIndex;
import com.betterschool.infrastructure.search.ProductSearchDoc;
import com.betterschool.infrastructure.search.SearchIndexName;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.SearchEntityType;
import com.betterschool.types.common.SearchPageVO;
import com.betterschool.types.common.SearchSyncMessage;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.exception.FrameworkException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 交易仓储（闲置商品 + 商品托管订单合一，一域一仓储）：持有 IProductDao / IProductOrderDao
 * 及其拆分出的按表 DAO（IProductFavoriteDao / IProductFeedIndexDao / IProductViewCountDao /
 * IProductViewHistoryDao / IProductOrderEventDao）+ outbox 表 DAO（{@link IMqOutboxDao}）与
 * 超时组件（{@link TimeoutTaskStore}，注册边直插 timeout_task）。
 * 跨两表的多写原子用例收成仓储方法，事务在实现内用编程式事务（默认 REQUIRED，与既有域服务事务同传播语义）。
 * PO / Mapper XML / DAO 位置不变。
 *
 * <p>本类同时承担计数合并器<b>商品浏览</b>那一组的落库方法（{@link #flushViewGroup}）：一组一个事务
 * 三步（{@code product_view_history} 明细 INSERT IGNORE → 全组刷 viewed_at → {@code product_view_count}
 * 加速列 += 新增数，market-feed-uv 票 02）。合并器实例由本类自己持有（构造后自建，出口绑定
 * {@code this::flushViewGroup}），不依赖任何跨仓储的分发层。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IdleRepository implements IIdleRepository {

    private final IProductDao productDao;
    private final IProductOrderDao productOrderDao;
    /** 商品收藏表（product_favorite 单表，原 IProductDao 拆分） */
    private final IProductFavoriteDao productFavoriteDao;
    /** 闲置 feed 异构索引表（product_feed_index 单表，原 IProductDao 拆分） */
    private final IProductFeedIndexDao productFeedIndexDao;
    /** 浏览 UV 计数窄表（product_view_count 单表，原 IProductDao 拆分） */
    private final IProductViewCountDao productViewCountDao;
    /** 浏览 UV 明细表（product_view_history 单表，原 IProductDao 拆分） */
    private final IProductViewHistoryDao productViewHistoryDao;
    /** 订单状态流转事件表（product_order_event 单表，原 IProductOrderDao 拆分） */
    private final IProductOrderEventDao productOrderEventDao;
    private final IProductInventoryDao productInventoryDao;
    private final IProductStockEventDao productStockEventDao;
    private final ObjectMapper objectMapper;
    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    /** 超时组件（技术能力：同层技术组件直用，不开 domain port） */
    private final TimeoutTaskStore timeoutTaskStore;
    /** 倒排索引技术组件（技术能力：本域仓储直接用，不为它开 domain port） */
    private final ISearchIndex searchIndex;
    private final TransactionTemplate transactionTemplate;
    /**
     * 商品浏览的合并引擎（通用 {@link MergeEngine}，本仓储独占一个实例：它只写 product_view_* 这几张表）。
     * 非 final：出口是 {@code this::flushViewGroup}，只能在依赖注入完成后再建。
     */
    private MergeEngine<CountEvent, LikeState> viewEngine;

    /**
     * 引擎由本类自建（通用引擎不是 Spring 托管对象，谁用谁 new），策略里只有两件业务知识：
     * 分组键 = 商品业务主键，落库 = 本类的 {@link #flushViewGroup}。
     */
    @Autowired
    void initViewEngine(MergeConfig mergeConfig) {
        this.viewEngine = new MergeEngine<>(mergeConfig, new MergeStrategy<>(
                "product-view",
                CountEvent::targetId,
                group -> flushViewGroup(group.get(0).targetId(), group)));
    }

    /** 停机排空：引擎不是 Spring 托管对象，销毁由持有者发起 */
    @PreDestroy
    void closeViewEngine() {
        if (viewEngine != null) {
            viewEngine.close();
        }
    }

    /** 排空等待（排障与集成测试读数用，业务路径不调）：true = 当前无在途件 */
    public boolean awaitIdle(long timeoutMs) {
        return viewEngine == null || viewEngine.awaitIdle(timeoutMs);
    }
    /** 卖家昵称头像批量回查（SQL 层零 JOIN：商品列表/详情的 bs_user 信息在这里多查一次拼装） */
    private final IUserDao userDao;

    // ---------- 商品 ----------

    @Override
    public void save(ProductEntity productEntity) {
        transactionTemplate.execute(status -> {
            // 业务 ID 落库前发号（uuid-biz-id 票 05）：随行一次写入，不再自增同值回写
            productEntity.setProductId(UUID.randomUUID().toString());
            Product product = toPO(productEntity);
            productDao.insert(product);
            // 库存台账行同事务诞生（multi-quantity-listing）：quantity 取入参（null = 1），INIT 流水建账
            int quantity = productEntity.getQuantity() == null ? 1 : productEntity.getQuantity();
            ProductInventory inventory = new ProductInventory();
            inventory.setProductId(productEntity.getProductId());
            inventory.setQuantity(quantity);
            inventory.setRemaining(quantity);
            productInventoryDao.insert(inventory);
            saveStockEvent(productEntity.getProductId(), "INIT", quantity, quantity,
                    null, productEntity.getUserId(), "SELLER");
            if (productEntity.getStatus() == ProductStatusVO.ON_SALE) {
                // 发布即可见：feed 索引行诞生 + 搜索同步消息，与落库同一事务
                productFeedIndexDao.upsertFeedIndexOnApprove(productEntity.getProductId());
                saveSearchSyncMessage(productEntity.getProductId());
            }
            return null;
        });
    }

    @Override
    public ProductEntity findById(String productId) {
        Product row = productDao.selectById(productId);
        fillProductExtras(row == null ? List.of() : List.of(row));
        return toProductEntity(row);
    }

    @Override
    public void deleteById(String productId) {
        transactionTemplate.execute(status -> {
            if (productDao.softDelete(productId) > 0) {
                // feed 索引行同事务不可见化（market-feed-uv 票 03）：行不存在（从未通过审）时 0 行静默
                syncFeedIndexState(productId, ProductStatusVO.DELETED);
            }
            return null;
        });
    }

    @Override
    public void markSold(String productId) {
        transactionTemplate.execute(status -> {
            if (productDao.markSold(productId) > 0) {
                // 标记已出 = 售罄：台账剩余清零 + ADJUST 流水（delta 按清零前剩余记账）
                ProductInventory before = productInventoryDao.selectByProductId(productId);
                if (before != null && before.getRemaining() > 0
                        && productInventoryDao.clearRemainingReturnDelta(productId) > 0) {
                    saveStockEvent(productId, "ADJUST", -before.getRemaining(), 0, null, null, "SELLER");
                }
                syncFeedIndexState(productId, ProductStatusVO.SOLD);
            }
            return null;
        });
    }

    @Override
    public boolean relist(String productId) {
        Boolean relisted = transactionTemplate.execute(status -> {
            ProductInventory inventory = productInventoryDao.selectByProductId(productId);
            if (inventory == null || inventory.getRemaining() == 0) {
                // 真卖完（remaining = 0）：无货不可重上架
                return false;
            }
            if (productDao.relist(productId) == 0) {
                return false; // 条件 UPDATE 0 行 = 状态已变化
            }
            // 索引行同事务翻回可见（行必存在：SOLD 前必经过 ON_SALE）
            syncFeedIndexState(productId, ProductStatusVO.ON_SALE);
            // 重新对外可见，落搜索同步消息（消费方按状态 upsert）
            saveSearchSyncMessage(productId);
            return true;
        });
        return Boolean.TRUE.equals(relisted);
    }

    @Override
    public void restock(String productId, int quantity, String actorId) {
        transactionTemplate.execute(status -> {
            Product productRow = productDao.selectById(productId);
            if (productRow == null || !ProductStatusVO.ON_SALE.name().equals(productRow.getState())) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅在售商品可加货");
            }
            if (productInventoryDao.restock(productId, quantity) == 0) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "库存台账行缺失，请联系管理员");
            }
            ProductInventory inventory = productInventoryDao.selectByProductId(productId);
            saveStockEvent(productId, "RESTOCK", quantity, inventory.getRemaining(), null, actorId, "SELLER");
            return null;
        });
    }

    @Override
    public void updateDraft(ProductEntity productEntity) {
        productDao.updateDraft(toPO(productEntity));
        // 草稿改件数：台账 quantity/remaining 一并覆盖（草稿期无订单可扣，无回补越界问题）
        if (productEntity.getQuantity() != null) {
            productInventoryDao.updateDraftQuantity(productEntity.getProductId(), productEntity.getQuantity());
        }
    }

    @Override
    public boolean submitDraft(String productId) {
        Boolean submitted = transactionTemplate.execute(status -> {
            boolean updated = productDao.submitDraft(productId) > 0;
            if (updated) {
                // 提交即上架可见：feed 索引行诞生/翻回 + 搜索同步消息，同一事务
                productFeedIndexDao.upsertFeedIndexOnApprove(productId);
                saveSearchSyncMessage(productId);
            }
            return updated;
        });
        return Boolean.TRUE.equals(submitted);
    }

    @Override
    public boolean takeDown(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean takenDown = productDao.takeDown(productId) > 0;
            if (takenDown) {
                // 在售→草稿（主动下架/到期汇入）：索引行必存在（ON_SALE 前必通过审），同事务不可见化
                syncFeedIndexState(productId, ProductStatusVO.DRAFT);
            }
            return takenDown;
        }));
    }

    @Override
    public List<String> listExpiredOnSaleIds(LocalDateTime before, int limit) {
        return productDao.selectExpiredOnSaleIds(before, limit);
    }

    @Override
    public boolean editOnSaleContent(ProductEntity productEntity) {
        // 手工组装编辑 PO（不用 toPO：未变更字段保持 null，动态 SQL 才不会覆盖）
        Product product = new Product();
        product.setProductId(productEntity.getProductId());
        product.setTitle(productEntity.getTitle());
        product.setDescription(productEntity.getDescription());
        product.setImages(productEntity.getImages() == null ? null : toImagesJson(productEntity.getImages()));
        product.setPrice(productEntity.getPrice());
        Boolean edited = transactionTemplate.execute(status -> {
            boolean updated = productDao.editOnSaleContent(product) > 0;
            if (updated) {
                // 改内容立即生效（状态不变，行必存在：ON_SALE 才可改内容）；
                // 混提的新价同事务刷新索引 price 快照，防价格排序漂移
                if (productEntity.getPrice() != null
                        && productFeedIndexDao.updateFeedIndexPrice(productEntity.getProductId(), productEntity.getPrice()) == 0) {
                    log.warn("[IdleRepository] 在售改内容的索引价格旁路落 0 行：索引行缺失（存量异常），交重建 job 兜底 productId={}",
                            productEntity.getProductId());
                }
            }
            return updated;
        });
        return Boolean.TRUE.equals(edited);
    }

    @Override
    public boolean updateOnSalePrice(String productId, BigDecimal price) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = productDao.updateOnSalePrice(productId, price) > 0;
            if (updated) {
                // 在售改价立即生效不进审核（现状语义）：索引 price 列同事务跟上，否则价格排序漂移
                if (productFeedIndexDao.updateFeedIndexPrice(productId, price) == 0) {
                    log.warn("[IdleRepository] 在售改价的索引旁路落 0 行：索引行缺失（存量异常），交重建 job 兜底 productId={}", productId);
                }
            }
            return updated;
        }));
    }

    @Override
    public List<ProductEntity> pageList(ProductCategoryVO category, BigDecimal minPrice, BigDecimal maxPrice,
                                        String sort, int offset, int limit) {
        String categoryName = category == null ? null : category.name();
        // hot 档排序键 view_count 在窄表（SQL 不能 JOIN 进来）：ON_SALE 全集 + 窄表批量回查 + 内存排序切片。
        // 旧直查路径仅作降级（索引不可用时），数据集有界，可接受
        List<Product> productList;
        if ("hot".equals(sort)) {
            List<Product> allOnSaleProductList = productDao.selectOnSaleRows(categoryName, minPrice, maxPrice);
            Map<String, Product> viewRows = viewCountRowsById(productViewCountDao.selectViewCountsByProductIds(
                    allOnSaleProductList.stream().map(Product::getProductId).toList()));
            productList = allOnSaleProductList.stream()
                    .sorted(Comparator
                            .comparing((Product p) -> viewCountOf(viewRows, p.getProductId())).reversed()
                            .thenComparing(Product::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(Product::getProductId, Comparator.reverseOrder()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        } else {
            productList = productDao.selectPage(categoryName, minPrice, maxPrice, sort, offset, limit);
        }
        fillProductExtras(productList);
        return productList.stream().map(this::toProductEntity).toList();
    }

    @Override
    public long count(ProductCategoryVO category, BigDecimal minPrice, BigDecimal maxPrice) {
        String categoryName = category == null ? null : category.name();
        return productDao.count(categoryName, minPrice, maxPrice);
    }

    @Override
    public List<ProductFeedIndexItemVO> scanFeedIndex(String sortType, ProductFeedCursorVO cursor, int limit) {
        return productFeedIndexDao.selectFeedIndex(sortType, cursor, limit);
    }

    /**
     * feed 索引全量重建（market-feed-uv 票 06，照 {@code PostRepository.rebuildFeedIndex} 先例）：
     * 四条语句一个事务——计数窄表校准（ODKU 补缺行 / 刷漂移 + 清孤儿）→ 清空索引 → 重灌投影。
     * 顺序是先校准后清灌：计数窄表是索引热度键的数据源，先算准再抄进重灌语句，一个事务里两次写只对
     * 读者呈现前后两个完整状态（清空用 DELETE 不用 TRUNCATE，后者隐式提交会毁掉原子性）
     */
    @Override
    public void rebuildFeedIndex() {
        transactionTemplate.executeWithoutResult(status -> {
            int recalibrated = productViewCountDao.recalibrateViewCounts();
            int orphans = productViewCountDao.deleteViewCountsMissingHistory();
            productFeedIndexDao.clearFeedIndex();
            int rebuilt = rebuildFeedIndexRows();
            log.info("[MarketFeedIndexRebuild] product_feed_index 清空重灌 {} 行；product_view_count 校准 {} 行、清理孤儿 {} 行",
                    rebuilt, recalibrated, orphans);
        });
    }

    /**
     * 重灌行集拼装（原 INSERT..SELECT 一步投影的多查形态）：行集 = ON_SALE 商品。
     * 两段取数——① product 全量 (product_id, state, create_time, price)，feed_time = create_time；
     * ② view_count 从计数窄表抄入（无行 = 0）——组装后批量写回
     */
    private int rebuildFeedIndexRows() {
        List<ProductFeedIndexRow> feedIndexRowList = new ArrayList<>();
        for (ProductFeedIndexRow candidate : productDao.selectRebuildRows()) {
            if (!ProductStatusVO.ON_SALE.name().equals(candidate.getState())) {
                // 草稿/已删商品不凭空进索引
                continue;
            }
            candidate.setFeedTime(candidate.getCreateTime());
            feedIndexRowList.add(candidate);
        }
        if (!feedIndexRowList.isEmpty()) {
            Map<String, Product> viewRows = viewCountRowsById(productViewCountDao.selectViewCountsByProductIds(
                    feedIndexRowList.stream().map(ProductFeedIndexRow::getProductId).toList()));
            for (ProductFeedIndexRow row : feedIndexRowList) {
                row.setViewCount(viewCountOf(viewRows, row.getProductId()));
            }
            for (int from = 0; from < feedIndexRowList.size(); from += REBUILD_INSERT_BATCH) {
                productFeedIndexDao.insertFeedIndexRows(feedIndexRowList.subList(from, Math.min(from + REBUILD_INSERT_BATCH, feedIndexRowList.size())));
            }
        }
        return feedIndexRowList.size();
    }

    @Override
    public boolean addFavorite(String productId, String userId) {
        return productFavoriteDao.insertFavorite(productId, userId) > 0;
    }

    @Override
    public boolean removeFavorite(String productId, String userId) {
        return productFavoriteDao.deleteFavorite(productId, userId) > 0;
    }

    @Override
    public boolean isFavorited(String productId, String userId) {
        return productFavoriteDao.existsFavorite(productId, userId) > 0;
    }

    @Override
    public long countFavorites(String productId) {
        return productFavoriteDao.countFavorites(productId);
    }

    @Override
    public List<ProductEntity> listFavorites(String userId, int offset, int limit) {
        // 收藏关系行主查 → 商品行两段查（原 INNER JOIN product 的行集与 state != 'DELETED' 谓词）
        List<String> favoriteIdList = productFavoriteDao.selectFavoriteProductIdsByUser(userId);
        if (favoriteIdList.isEmpty()) {
            return List.of();
        }
        Map<String, Product> productsById = productDao.selectByIds(favoriteIdList).stream()
                .collect(Collectors.toMap(Product::getProductId, product -> product, (a, b) -> a));
        List<Product> visibleProductList = new ArrayList<>(favoriteIdList.size());
        for (String productId : favoriteIdList) {
            Product product = productsById.get(productId);
            if (product != null && !ProductStatusVO.DELETED.name().equals(product.getState())) {
                visibleProductList.add(product);
            }
        }
        List<Product> productList = visibleProductList.stream().skip(offset).limit(limit).toList();
        fillProductExtras(productList);
        return productList.stream().map(this::toProductEntity).toList();
    }

    @Override
    public List<ProductEntity> pageBySeller(String userId, int offset, int limit) {
        List<Product> productList = productDao.selectPageBySeller(userId, offset, limit);
        fillProductExtras(productList);
        return productList.stream().map(this::toProductEntity).toList();
    }

    @Override
    public long countBySeller(String userId) {
        return productDao.countBySeller(userId);
    }

    @Override
    public List<ProductEntity> pageOnSaleByUser(String userId, int offset, int limit) {
        List<Product> productList = productDao.selectOnSalePageByUser(userId, offset, limit);
        fillProductExtras(productList);
        return productList.stream().map(this::toProductEntity).toList();
    }

    @Override
    public long countOnSaleByUser(String userId) {
        return productDao.countOnSaleByUser(userId);
    }

    @Override
    public void recordProductView(String productId, String userId) {
        // 浏览计数（UV 口径，统一等待语义）：commit 等终态，一组一个事务——明细表 INSERT IGNORE
        // + viewed_at 刷新 + 加速列 += 新增数。匿名访问落不了明细行（product_view_history 主键的一半
        // 是 user_id），按「INSERT 成功才算浏览」的口径不计入（商品语义变更：旧口径匿名也累加 PV）；
        // 失败/超时抛可重试，重放被明细唯一键吸收（spec：浏览类从「允许丢」翻转为 commit 语义）
        if (userId == null) {
            return;
        }
        // 商品业务 ID 已是 UUID 字符串（uuid-biz-id 票 05）：合并器键直传
        writeView(CountAction.PRODUCT_VIEW, productId, userId);
    }

    /**
     * 浏览类写入：提交给合并器并等终态。{@code TIMEOUT} / {@code FAILED} 都抛可重试——
     * 前者件大概率仍会落库，后者确定没落；重放被 UV 明细唯一键吸收，不会重复计。
     * {@code BUFFER_FULL} / {@code CLOSED} 不该冒到这里：引擎在入队阶段已就地降级。
     */
    private void writeView(CountAction action, String rowId, String userId) {
        MergeResult<LikeState> outcome = viewEngine.commit(new CountEvent(action, rowId, userId));
        if (!outcome.isCompleted()) {
            throw retryable(action, rowId, "确定没落（整组已回滚 / 件未入队），可安全重试");
        }
    }

    /** 可重试：行一定没动（或重试会被 UV 明细唯一键吸收），对外统一「系统繁忙，请稍后重试」 */
    private static BusinessException retryable(CountAction action, String rowId, String detail) {
        log.warn("商品浏览写入需重试：action={}, rowId={}, 原因={}", action, rowId, detail);
        return new BusinessException(ResponseCode.SYSTEM_ERROR, ResponseCode.SYSTEM_ERROR.getInfo());
    }

    @Override
    public List<ProductEntity> listByIds(List<String> productIdList) {
        List<Product> productList = productDao.selectByIds(productIdList);
        fillProductExtras(productList);
        return productList.stream().map(this::toProductEntity).toList();
    }

    @Override
    public List<String> selectViewedProductIds(String userId, List<String> productIdList) {
        if (userId == null || productIdList == null || productIdList.isEmpty()) {
            return List.of();
        }
        return productViewHistoryDao.selectViewedProductIds(userId, productIdList);
    }

    @Override
    public List<String> listIdsAfter(String lastProductId, int limit) {
        return productDao.selectIdsAfter(lastProductId, limit);
    }

    @Override
    public void saveSearchSyncMessage(String productId) {
        saveOutbox(Constants.SEARCH_SYNC_TOPIC,
                new SearchSyncMessage(SearchEntityType.PRODUCT.name().toLowerCase(), productId));
    }

    /** 事务内落一行 outbox，提交后同步发送并标记 SENT；失败留 PENDING 交 outboxScan 兜底 */
    private void saveOutbox(String destination, Object payload) {
        MqOutbox mqOutbox = MqOutbox.pending(destination, payload, objectMapper);
        mqOutboxDao.insert(mqOutbox);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendOutbox(mqOutbox);
            }
        });
    }

    private void sendOutbox(MqOutbox mqOutbox) {
        boolean sent;
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(mqOutbox.getDestination(), mqOutbox.getPayload());
            sent = sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
            if (!sent) {
                log.warn("outbox 同步发送未确认 msgId={}, status={}", mqOutbox.getMsgId(),
                        sendResult == null ? null : sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.warn("outbox 同步发送失败 msgId={}", mqOutbox.getMsgId(), e);
            sent = false;
        }
        if (sent) {
            mqOutboxDao.markSentByMsgId(mqOutbox.getMsgId());
        } else if (mqOutbox.getRetryCount() + 1 >= MqOutbox.MAX_RETRY_COUNT) {
            mqOutboxDao.markDeadBatch(List.of(mqOutbox.getId()));
        } else {
            mqOutboxDao.incrementRetryBatch(List.of(mqOutbox.getId()));
        }
    }

    @Override
    public SearchPageVO<String> searchProductIds(String keyword, int page, int pageSize) {
        // ES 侧 status 过滤只是冗余兜底（索引可能短暂滞后），最终公开判定在领域服务回库后做
        return searchIndex.search(SearchIndexName.PRODUCT, SearchIndexName.PRODUCT_SEARCH_FIELDS,
                List.of(ProductStatusVO.ON_SALE.name()), keyword, page, pageSize);
    }

    @Override
    public void syncProductSearchDoc(String productId) {
        ProductEntity productEntity = findById(productId);
        if (productEntity != null && productEntity.getStatus() == ProductStatusVO.ON_SALE) {
            searchIndex.bulkUpsert(SearchIndexName.PRODUCT, List.of(toProductSearchDoc(productEntity)),
                    ProductSearchDoc::getProductId);
        } else {
            searchIndex.deleteDoc(SearchIndexName.PRODUCT, productId);
        }
    }

    @Override
    public int rebuildProductSearchIndex() {
        searchIndex.recreate(SearchIndexName.PRODUCT);
        int total = 0;
        String cursor = "";
        while (true) {
            List<String> idList = listIdsAfter(cursor, ISearchIndex.DEFAULT_REBUILD_BATCH_SIZE);
            if (idList.isEmpty()) {
                break;
            }
            // 批内一次 IN 回查 + Java 侧过滤（SQL 层零 JOIN 的收口点），游标推进到空批是唯一终止条件
            List<ProductSearchDoc> docList = listByIds(idList).stream()
                    .filter(product -> product.getStatus() == ProductStatusVO.ON_SALE)
                    .map(this::toProductSearchDoc)
                    .toList();
            searchIndex.bulkUpsert(SearchIndexName.PRODUCT, docList, ProductSearchDoc::getProductId);
            total += docList.size();
            cursor = idList.get(idList.size() - 1);
        }
        return total;
    }

    /** 实体 → 索引文档：只取检索/过滤字段，价格/图片等展示字段不进索引（避免双写漂移） */
    private ProductSearchDoc toProductSearchDoc(ProductEntity productEntity) {
        ProductSearchDoc doc = new ProductSearchDoc();
        doc.setProductId(productEntity.getProductId());
        doc.setTitle(productEntity.getTitle());
        doc.setDescription(productEntity.getDescription());
        doc.setTradeLocation(productEntity.getTradeLocation());
        doc.setCategory(productEntity.getCategory().name());
        doc.setStatus(productEntity.getStatus().name());
        doc.setCreatedAt(productEntity.getCreatedAt());
        return doc;
    }

    // ---------- 计数合并器的落库方法：商品浏览（UV 口径，market-feed-uv 票 02） ----------

    /**
     * 一组商品浏览（{@link com.betterschool.infrastructure.adapter.repository.post.PostRepository}
     * {@code viewPosts} 的镜像）：一组一个事务三步——
     * <ol>
     *   <li>{@code INSERT IGNORE INTO product_view_history}（影响行数 = 本组<b>真正新增</b>的 UV 数，
     *       重复浏览撞 {@code (user_id, product_id)} 主键被裁决掉）；</li>
     *   <li>{@code UPDATE viewed_at}（本组全部键，含刚插入的——对重复浏览是唯一可见效果）；</li>
     *   <li>{@code product_view_count += 新增数}（加速列恒等于明细行数）；</li>
     *   <li>{@code product_feed_index.view_count += 新增数}（热度排序键旁路 ±N，market-feed-uv 票 03，
     *       命中 0 行落 warn 交重建 job）。</li>
     * </ol>
     *
     * <p><b>为什么计数按「影响行数」而不是「事件数」加</b>：前端双击、HTTP 重试、降级并发写都会产生
     * 重复键，{@code INSERT IGNORE} 的影响行数恰好是本组真正插入的行数，加速列与明细行条数就永远对得上。
     * 失败/超时由调用方重试，重放撞明细唯一键被吸收、不会重复计。
     *
     * <p><b>为什么要回一组占位结果</b>：浏览件没有「已赞 / 重复」可分，但引擎把「null / 缺位」
     * 当作漏回填并补 {@code FAILED}——那会让 {@code flushed} 漏掉这些件，合并比与
     * 「accepted − flushed」两个读数同时失真。
     *
     * <p><b>不再写主表列</b>：{@code product.view_count} 旧 PV 列随本票停写（读路径已换源
     * {@code product_view_count}），删列随收口票 06。
     */
    public List<LikeState> flushViewGroup(String productId, List<CountEvent> countEventList) {
        // 合并器键即商品业务 ID 字符串（UUID，uuid-biz-id 票 05 起直传，过渡期的 parseLong 缝合已拆）
        return transactionTemplate.execute(status -> {
            List<String> userIdList = distinctUserIds(countEventList);
            int inserted = productViewHistoryDao.insertProductViewHistory(productId, userIdList);
            productViewHistoryDao.touchProductViewedAt(productId, userIdList);
            if (inserted > 0) {
                productViewCountDao.upsertProductViewCount(productId, inserted);
                // 热度排序键旁路 ±N（market-feed-uv 票 03）：索引行 view_count 同事务跟上
                syncFeedIndexViewCount(productId, inserted);
            }
            return Collections.nCopies(countEventList.size(), LikeState.APPLIED);
        });
    }

    /**
     * feed 索引行状态快照（market-feed-uv 票 03）：条件 UPDATE 命中后同事务镜像 product 的新状态。
     * 纯 UPDATE 不 upsert——行不存在的常态（从未通过审的待审/驳回/草稿/草稿软删）0 行静默，
     * 不在写路径凭空补行
     */
    private void syncFeedIndexState(String productId, ProductStatusVO state) {
        productFeedIndexDao.updateFeedIndexState(productId, state.name());
    }

    /**
     * feed 索引行热度键旁路 ±N（浏览没有取消语义，恒 +）：纯 UPDATE 不 upsert。命中 0 行 =
     * 索引行缺失（未过审商品被本人浏览、或存量异常），落 warn 交重建 job（票 06），不在写路径补行
     */
    private void syncFeedIndexViewCount(String productId, int delta) {
        if (productFeedIndexDao.updateFeedIndexViewCount(productId, delta) == 0) {
            log.warn("[IdleRepository] 商品浏览旁路落 0 行：索引行缺失（未过审商品的浏览或存量异常），交重建 job 兜底 productId={}", productId);
        }
    }

    /** 一组内的去重浏览者（同组同人是重复件，明细表主键也只收得下一行） */
    private List<String> distinctUserIds(List<CountEvent> countEventList) {
        return countEventList.stream().map(CountEvent::userId).distinct().toList();
    }

    // ---------- 商品托管订单 ----------

    @Override
    public void saveOrder(ProductOrderEntity orderEntity) {
        // 业务 ID 落库前发号（uuid-biz-id 票 05）：随行一次写入，不再自增同值回写
        orderEntity.setOrderId(UUID.randomUUID().toString());
        ProductOrder order = toPO(orderEntity);
        productOrderDao.insert(order);
        // 创建事件：from_state 为 NULL（sql.md 口径），操作方恒买家
        saveEvent(order.getProductOrderId(), "CREATED", null, order.getState(),
                order.getBuyerId(), "BUYER");
    }

    @Override
    public ProductOrderEntity findOrderById(String orderId) {
        ProductOrder row = productOrderDao.selectById(orderId);
        fillOrderUserExtras(row == null ? List.of() : List.of(row));
        return toOrderEntity(row);
    }

    @Override
    public boolean payOrderById(String orderId, String buyerId) {
        boolean updated = productOrderDao.payById(orderId, buyerId) > 0;
        if (updated) {
            saveEvent(orderId, "PAID", "PENDING_PAYMENT", "PAID", buyerId, "BUYER");
        }
        return updated;
    }

    @Override
    public boolean markDeliveredOrderById(String orderId, String sellerId) {
        boolean updated = productOrderDao.markDeliveredById(orderId, sellerId) > 0;
        if (updated) {
            saveEvent(orderId, "DELIVERED", "PAID", "DELIVERED", sellerId, "SELLER");
        }
        return updated;
    }

    @Override
    public boolean confirmOrderById(String orderId, String buyerId) {
        boolean updated = productOrderDao.confirmById(orderId, buyerId) > 0;
        if (updated) {
            saveEvent(orderId, "COMPLETED", "DELIVERED", "COMPLETED", buyerId, "BUYER");
        }
        return updated;
    }

    @Override
    public boolean cancelOrderById(String orderId, String buyerId) {
        // 取消允许 PENDING_PAYMENT/PAID 两个前态，前态要落事件必须在 UPDATE 之前读（更新后该行已是新态）
        ProductOrder prior = productOrderDao.selectById(orderId);
        String fromState = prior == null ? null : prior.getState();
        boolean updated = productOrderDao.cancelById(orderId, buyerId) > 0;
        if (updated) {
            saveEvent(orderId, "CANCELLED", fromState, "CANCELLED", buyerId, "BUYER", "BUYER_CANCEL");
        }
        return updated;
    }

    @Override
    public boolean cancelPendingPaymentOrder(String orderId) {
        boolean updated = productOrderDao.cancelPendingPayment(orderId) > 0;
        if (updated) {
            saveEvent(orderId, "CANCELLED", "PENDING_PAYMENT", "CANCELLED", null, "SYSTEM", "PAY_TIMEOUT");
        }
        return updated;
    }

    @Override
    public List<ProductOrderEntity> listMineByBuyer(String userId, int offset, int limit) {
        List<ProductOrder> productOrderList = productOrderDao.selectMineByBuyer(userId, offset, limit);
        fillOrderUserExtras(productOrderList);
        return productOrderList.stream().map(this::toOrderEntity).toList();
    }

    @Override
    public long countMineByBuyer(String userId) {
        return productOrderDao.countMineByBuyer(userId);
    }

    @Override
    public List<ProductOrderEntity> listMineBySeller(String userId, int offset, int limit) {
        List<ProductOrder> productOrderList = productOrderDao.selectMineBySeller(userId, offset, limit);
        fillOrderUserExtras(productOrderList);
        return productOrderList.stream().map(this::toOrderEntity).toList();
    }

    @Override
    public long countMineBySeller(String userId) {
        return productOrderDao.countMineBySeller(userId);
    }

    @Override
    public void schedulePayTimeout(String orderId, long delaySeconds) {
        // 业务事务内直插超时任务行（内嵌超时中心，注册 outbox 已删）：与业务写同一条连接同一事务，
        // 回滚即无行、提交即被扫描可见；同键重入的三分支幂等（新插入/在途不改期/终态复活）在仓储内裁决；
        // 时刻一律数据库时钟，Java 只传时长
        timeoutTaskStore.register(TimeoutTaskEntity.newWaiting(
                Constants.PRODUCT_ORDER_PAY_TIMEOUT, orderId, delaySeconds));
    }

    // ---------- 跨商品、库存与订单的原子用例 ----------

    @Override
    public String createOrderWithInventory(ProductOrderEntity orderEntity, String productId, int payTimeoutMinutes) {
        transactionTemplate.execute(status -> {
            saveOrder(orderEntity);
            // 台账条件 UPDATE 扣减（multi-quantity-listing）：remaining >= 扣减量才生效，
            // 并发下单在台账行锁上排队、条件不过即 0 行——防超卖唯一闸门，0 行即抛并回滚整单
            int quantity = orderEntity.getQuantity() == null ? 1 : orderEntity.getQuantity();
            if (productInventoryDao.deduct(productId, quantity) == 0) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "库存不足，快去逛逛别的吧");
            }
            ProductInventory inventory = productInventoryDao.selectByProductId(productId);
            saveStockEvent(productId, "DEDUCT", -quantity, inventory.getRemaining(),
                    orderEntity.getOrderId(), orderEntity.getBuyerId(), "BUYER");
            if (inventory.getRemaining() == 0 && productDao.markSold(productId) > 0) {
                // 售罄：商品退出可见态，索引行同事务翻 SOLD + 搜索同步消息（消费方删文档）
                syncFeedIndexState(productId, ProductStatusVO.SOLD);
                saveSearchSyncMessage(productId);
            }
            // "15 分钟未支付"超时任务与下单同事务直插 timeout_task（内嵌超时中心）：
            // Java 只传时长，到期时刻由 SQL 侧 TIMESTAMPADD 按数据库时钟落库
            schedulePayTimeout(orderEntity.getOrderId(), payTimeoutMinutes * 60L);
            return null;
        });
        return orderEntity.getOrderId();
    }

    @Override
    public boolean cancelOrderWithSearchSync(String orderId, String buyerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!cancelOrderById(orderId, buyerId)) {
                return false;
            }
            ProductOrder order = productOrderDao.selectById(orderId);
            // 台账回补（按订单 quantity），回补后 remaining > 0 且商品 SOLD 时翻回 ON_SALE
            restoreStockWithEvent(order.getProductId(), order.getQuantity(), orderId, buyerId, "BUYER");
            // 搜索同步消息幂等（消费方按状态 upsert）：翻回在售则重新可搜，未翻则无副作用
            saveSearchSyncMessage(order.getProductId());
            return true;
        }));
    }

    @Override
    public boolean cancelPendingPaymentWithSearchSync(String orderId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!cancelPendingPaymentOrder(orderId)) {
                return false;
            }
            ProductOrder order = productOrderDao.selectById(orderId);
            restoreStockWithEvent(order.getProductId(), order.getQuantity(), orderId, null, "SYSTEM");
            saveSearchSyncMessage(order.getProductId());
            return true;
        }));
    }

    /** 孤儿扫描输入（ADR-0016 两段式）：只读，无事务 */
    @Override
    public List<ProductOrderEntity> listRecentSettled(int windowMinutes, int limit) {
        return productOrderDao.selectRecentSettled(windowMinutes, limit).stream().map(this::toOrderEntity).toList();
    }

    /** 订单最新一条状态流转事件（只读，无事务） */
    @Override
    public ProductOrderEventVO latestEventOf(String orderId) {
        ProductOrderEvent latest = productOrderEventDao.selectLatestEvent(orderId);
        return latest == null ? null : new ProductOrderEventVO(latest.getEventType(), latest.getFromState());
    }

    /**
     * 关单回补共用路径：台账 remaining 回补（LEAST 钳在 quantity，防重复回补越界）+ RELEASE 流水
     * + 回补后售罄商品翻回 ON_SALE（索引行随行翻回；0 行 = 商品仍是 ON_SALE/已被软删，无妨）
     */
    private void restoreStockWithEvent(String productId, Integer quantity, String orderId,
                                       String actorId, String actorRole) {
        int quantityToRestore = quantity == null ? 1 : quantity;
        ProductInventory before = productInventoryDao.selectByProductId(productId);
        if (productInventoryDao.restore(productId, quantityToRestore) == 0) {
            // 台账行缺失（存量异常）：不落流水，交重建/人工处理
            log.warn("[ProductOrder] 关单回补落 0 行：库存台账行缺失 productId={} orderId={}", productId, orderId);
            return;
        }
        ProductInventory after = productInventoryDao.selectByProductId(productId);
        int delta = after.getRemaining() - (before == null ? 0 : before.getRemaining());
        saveStockEvent(productId, "RELEASE", delta, after.getRemaining(), orderId, actorId, actorRole);
        if (delta > 0 && productDao.restoreFromSold(productId) > 0) {
            syncFeedIndexState(productId, ProductStatusVO.ON_SALE);
        }
    }

    // ---------- 商品状态变更 + 搜索同步 ----------

    @Override
    public boolean takeDownWithSearchSync(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!takeDown(productId)) {
                return false;
            }
            saveSearchSyncMessage(productId);
            return true;
        }));
    }

    @Override
    public void editOnSaleContentWithSearchSync(ProductEntity productEntity) {
        transactionTemplate.execute(status -> {
            if (!editOnSaleContent(productEntity)) {
                // 0 行即抛，在事务内回滚（异常点与改造前一致，不在域内抛）
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
            }
            // 改内容立即生效，同事务落搜索同步消息（消费方按状态 upsert）
            saveSearchSyncMessage(productEntity.getProductId());
            return null;
        });
    }

    @Override
    public void deleteWithSearchSync(String productId, boolean publiclyVisible) {
        transactionTemplate.execute(status -> {
            deleteById(productId);
            // 在售商品对外可见过，软删后需同步删除搜索文档；草稿从未对外可见，无需同步
            if (publiclyVisible) {
                saveSearchSyncMessage(productId);
            }
            return null;
        });
    }

    @Override
    public void markSoldWithSearchSync(String productId) {
        transactionTemplate.execute(status -> {
            markSold(productId);
            // 售出后商品退出搜索结果，落搜索同步消息（消费方删除 ES 文档）
            saveSearchSyncMessage(productId);
            return null;
        });
    }

    // ---------- 拼装辅助：浏览窄表 / 卖家信息（SQL 层零 JOIN 的 Java 收口点） ----------

    /** 重灌行集批量写回的分片大小（防 foreach 语句过长） */
    private static final int REBUILD_INSERT_BATCH = 500;

    /**
     * 商品行补齐浏览 UV 加速列 + 库存台账剩余件数 + 卖家昵称头像：product_view_count / product_inventory /
     * bs_user 各一次 IN 批量回查。缺行补 0 / 补 null（与原 LEFT JOIN 合成口径一致）；列表与详情共用
     */
    private void fillProductExtras(List<Product> productList) {
        if (productList.isEmpty()) {
            return;
        }
        Map<String, Product> viewRows = viewCountRowsById(
                productViewCountDao.selectViewCountsByProductIds(productList.stream().map(Product::getProductId).toList()));
        Map<String, ProductInventory> inventoryRows = productInventoryDao
                .selectByProductIds(productList.stream().map(Product::getProductId).toList()).stream()
                .collect(Collectors.toMap(ProductInventory::getProductId, row -> row, (a, b) -> a));
        List<String> userIdList = productList.stream().map(Product::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<String, User> users = userIdList.isEmpty() ? Map.of() : userDao.selectByIds(userIdList).stream()
                .collect(Collectors.toMap(User::getUserId, user -> user, (a, b) -> a));
        for (Product row : productList) {
            row.setViewCount(viewCountOf(viewRows, row.getProductId()));
            ProductInventory inventory = inventoryRows.get(row.getProductId());
            row.setRemainingQuantity(inventory == null ? null : inventory.getRemaining());
            User user = users.get(row.getUserId());
            if (user != null) {
                row.setSellerNickname(user.getNickname());
                row.setSellerAvatar(user.getAvatar());
            }
        }
    }

    /** 部分列计数行 → productId 索引 */
    private static Map<String, Product> viewCountRowsById(List<Product> viewCountRowList) {
        return viewCountRowList.stream().collect(Collectors.toMap(Product::getProductId, row -> row, (a, b) -> a));
    }

    /** 窄表缺行 = 计数 0（与原 LEFT JOIN IFNULL(x, 0) 同口径） */
    private static Integer viewCountOf(Map<String, Product> viewRows, String productId) {
        Product row = viewRows.get(productId);
        return row == null ? 0 : row.getViewCount();
    }

    /**
     * 订单行补齐买卖双方昵称头像（原 SELECT 里 bs_user ×2 的 LEFT JOIN 列）：
     * 买卖双方 userId 合并一次 bs_user 批量回查，缺行补 null（与 LEFT JOIN 一致）
     */
    private void fillOrderUserExtras(List<ProductOrder> productOrderList) {
        if (productOrderList.isEmpty()) {
            return;
        }
        List<String> userIdList = new ArrayList<>(productOrderList.size() * 2);
        for (ProductOrder row : productOrderList) {
            userIdList.add(row.getBuyerId());
            userIdList.add(row.getSellerId());
        }
        Map<String, User> users = userDao.selectByIds(
                userIdList.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getUserId, user -> user, (a, b) -> a));
        for (ProductOrder row : productOrderList) {
            User buyer = users.get(row.getBuyerId());
            User seller = users.get(row.getSellerId());
            row.setBuyerNickname(buyer == null ? null : buyer.getNickname());
            row.setBuyerAvatar(buyer == null ? null : buyer.getAvatar());
            row.setSellerNickname(seller == null ? null : seller.getNickname());
            row.setSellerAvatar(seller == null ? null : seller.getAvatar());
        }
    }

    // ---------- PO / Entity 转换 ----------

    private Product toPO(ProductEntity productEntity) {
        Product product = new Product();
        product.setProductId(productEntity.getProductId());
        product.setUserId(productEntity.getUserId());
        product.setTitle(productEntity.getTitle());
        product.setDescription(productEntity.getDescription());
        product.setImages(toImagesJson(productEntity.getImages()));
        product.setPrice(productEntity.getPrice());
        // 草稿行可空：tradeLocation/category 未填时存 NULL，提交时才要求必填
        product.setTradeLocation(productEntity.getTradeLocation());
        product.setCategory(productEntity.getCategory() == null ? null : productEntity.getCategory().name());
        product.setState(productEntity.getStatus().name());
        product.setViewCount((int) productEntity.getViewCount());
        return product;
    }

    private ProductEntity toProductEntity(Product product) {
        if (product == null) {
            return null;
        }
        ProductEntity productEntity = new ProductEntity();
        productEntity.setProductId(product.getProductId());
        productEntity.setUserId(product.getUserId());
        productEntity.setTitle(product.getTitle());
        productEntity.setDescription(product.getDescription());
        productEntity.setImages(toImagesList(product.getImages()));
        productEntity.setPrice(product.getPrice());
        productEntity.setTradeLocation(product.getTradeLocation());
        productEntity.setCategory(product.getCategory() == null ? null : ProductCategoryVO.valueOf(product.getCategory()));
        productEntity.setStatus(ProductStatusVO.valueOf(product.getState()));
        productEntity.setViewCount(product.getViewCount());
        productEntity.setRemainingQuantity(product.getRemainingQuantity());
        productEntity.setCreatedAt(product.getCreateTime());
        productEntity.setSellerNickname(product.getSellerNickname());
        productEntity.setSellerAvatar(product.getSellerAvatar());
        return productEntity;
    }

    private ProductOrder toPO(ProductOrderEntity orderEntity) {
        ProductOrder order = new ProductOrder();
        order.setProductOrderId(orderEntity.getOrderId());
        order.setBuyerId(orderEntity.getBuyerId());
        order.setSellerId(orderEntity.getSellerId());
        order.setProductId(orderEntity.getProductId());
        order.setPrice(orderEntity.getPrice());
        order.setQuantity(orderEntity.getQuantity());
        order.setProductTitle(orderEntity.getProductTitle());
        order.setProductDescription(orderEntity.getProductDescription());
        order.setProductImages(toOrderImagesJson(orderEntity.getProductImages()));
        order.setProductTradeLocation(orderEntity.getProductTradeLocation());
        order.setProductCategory(orderEntity.getProductCategory() == null ? null : orderEntity.getProductCategory().name());
        order.setState(orderEntity.getStatus().name());
        return order;
    }

    private ProductOrderEntity toOrderEntity(ProductOrder order) {
        if (order == null) {
            return null;
        }
        ProductOrderEntity orderEntity = new ProductOrderEntity();
        orderEntity.setOrderId(order.getProductOrderId());
        orderEntity.setBuyerId(order.getBuyerId());
        orderEntity.setSellerId(order.getSellerId());
        orderEntity.setProductId(order.getProductId());
        orderEntity.setPrice(order.getPrice());
        orderEntity.setQuantity(order.getQuantity());
        orderEntity.setProductTitle(order.getProductTitle());
        orderEntity.setProductDescription(order.getProductDescription());
        orderEntity.setProductImages(toOrderImagesList(order.getProductImages()));
        orderEntity.setProductTradeLocation(order.getProductTradeLocation());
        orderEntity.setProductCategory(order.getProductCategory() == null ? null : ProductCategoryVO.valueOf(order.getProductCategory()));
        orderEntity.setStatus(ProductOrderStatusVO.valueOf(order.getState()));
        orderEntity.setCreatedAt(order.getCreateTime());
        orderEntity.setBuyerNickname(order.getBuyerNickname());
        orderEntity.setBuyerAvatar(order.getBuyerAvatar());
        orderEntity.setSellerNickname(order.getSellerNickname());
        orderEntity.setSellerAvatar(order.getSellerAvatar());
        orderEntity.setProductImage(order.getProductImage());
        return orderEntity;
    }

    private String toImagesJson(List<String> imageList) {
        try {
            return objectMapper.writeValueAsString(imageList);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("商品图片序列化失败", e);
        }
    }

    private List<String> toImagesList(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(imagesJson, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            log.warn("商品图片反序列化失败，按空处理：{}", imagesJson, e);
            return List.of();
        }
    }

    /**
     * 订单侧图片快照序列化（与商品侧刻意分开，两实现原样搬入前语义不同，不改动）：
     * 空列表落 SQL NULL（列可空，见 docs/db/idle.md）；失败抛 FrameworkException。
     */
    private String toOrderImagesJson(List<String> imageList) {
        if (imageList == null || imageList.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(imageList);
        } catch (JsonProcessingException e) {
            throw new FrameworkException("商品图片快照序列化失败", e);
        }
    }

    private List<String> toOrderImagesList(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(imagesJson, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("商品图片快照反序列化失败，按空处理：{}", imagesJson, e);
            return List.of();
        }
    }

    /** 订单状态流转事件落表（与条件 UPDATE 同事务，只插入；from_state 为 NULL 表示创建事件） */
    private void saveEvent(String orderId, String eventType, String fromState, String toState,
                           String actorId, String actorRole) {
        saveEvent(orderId, eventType, fromState, toState, actorId, actorRole, null);
    }

    private void saveEvent(String orderId, String eventType, String fromState, String toState,
                           String actorId, String actorRole, String reasonType) {
        ProductOrderEvent event = new ProductOrderEvent();
        event.setOrderId(orderId);
        event.setEventType(eventType);
        event.setFromState(fromState);
        event.setToState(toState);
        event.setActorId(actorId);
        event.setActorRole(actorRole);
        event.setReasonType(reasonType);
        productOrderEventDao.insertOrderEvent(event);
    }

    /** 库存流水落表（multi-quantity-listing：与台账变更同事务双写，只插入；after_remaining 是对账锚点） */
    private void saveStockEvent(String productId, String eventType, int delta, int afterRemaining,
                                String orderId, String actorId, String actorRole) {
        ProductStockEvent event = new ProductStockEvent();
        event.setProductId(productId);
        event.setEventType(eventType);
        event.setDelta(delta);
        event.setAfterRemaining(afterRemaining);
        event.setOrderId(orderId);
        event.setActorId(actorId);
        event.setActorRole(actorRole);
        productStockEventDao.insertStockEvent(event);
    }
}
