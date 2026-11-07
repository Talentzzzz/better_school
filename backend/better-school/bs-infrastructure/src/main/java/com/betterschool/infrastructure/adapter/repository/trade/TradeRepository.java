package com.betterschool.infrastructure.adapter.repository.trade;

import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.search.model.valobj.SearchEntityType;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductCategoryVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.domain.trade.model.valobj.ProductOrderStatusVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import com.betterschool.infrastructure.adapter.merge.CountAction;
import com.betterschool.infrastructure.adapter.merge.CountEvent;
import com.betterschool.infrastructure.adapter.merge.LikeState;
import com.betterschool.infrastructure.adapter.merge.ViewMergeWriter;
import com.betterschool.infrastructure.dao.product.IProductDao;
import com.betterschool.infrastructure.dao.product.po.ProductPO;
import com.betterschool.infrastructure.dao.productorder.IProductOrderDao;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrderEventPO;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrderPO;
import com.betterschool.infrastructure.adapter.repository.moderation.ReviewTaskPoolStore;
import com.betterschool.infrastructure.dao.moderation.IModerationDao;
import com.betterschool.infrastructure.dao.moderation.po.ContentReviewPO;
import com.betterschool.infrastructure.dao.product.IProductDao;
import com.betterschool.infrastructure.dao.product.po.ProductFeedIndexRowPO;
import com.betterschool.infrastructure.dao.product.po.ProductPO;
import com.betterschool.infrastructure.dao.productorder.IProductOrderDao;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrderEventPO;
import com.betterschool.infrastructure.dao.productorder.po.ProductOrderPO;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.UserPO;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.exception.FrameworkException;
import com.betterschool.types.id.IIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
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
import java.util.stream.Collectors;

/**
 * 交易仓储（闲置商品 + 商品托管订单合一，一域一仓储）：持有 IProductDao + IProductOrderDao +
 * 共享 outbox（OutboxMessageStore）与超时任务仓储（ITimeoutRepository，注册边直插 timeout_task）。
 * 跨两表的多写原子用例收成仓储方法，事务在实现内用编程式事务（默认 REQUIRED，与既有域服务事务同传播语义）。
 * PO / Mapper XML / DAO 位置不变。
 *
 * <p>本类同时承担计数合并器<b>商品浏览</b>那一组的落库方法（{@link #flushViewGroup}）：一组一个事务
 * 三步（{@code product_view_history} 明细 INSERT IGNORE → 全组刷 viewed_at → {@code product_view_count}
 * 加速列 += 新增数，market-feed-uv 票 02）。它不是 {@code ICountFlushHandler} 的实现，
 * 因为那个接口的方法签名带着基础设施的合并事件类型，而 {@code ITradeRepository} 是域接口——分发由
 * {@code CountFlushDispatcher} 在基础设施层完成。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TradeRepository implements ITradeRepository {

    private final IProductDao productDao;
    private final IProductOrderDao productOrderDao;
    private final ObjectMapper objectMapper;
    private final OutboxMessageStore outboxMessageStore;
    private final ITimeoutRepository timeoutRepository;
    private final ReviewTaskPoolStore reviewTaskPoolStore;
    private final TransactionTemplate transactionTemplate;
    /** 浏览类写入的唯一通道（fire-and-forget，窗口后一组一个事务三步落 UV 明细 + 加速列） */
    private final ViewMergeWriter viewMergeWriter;
    /** 业务 ID 发号器（uuid-biz-id 票 05）：36 位小写 UUID 字符串，insert 前生成、随行一次写入 */
    private final IIdGenerator idGenerator;
    /** 卖家昵称头像批量回查（SQL 层零 JOIN：商品列表/详情的 bs_user 信息在这里多查一次拼装） */
    private final IUserDao userDao;
    /** feed 索引重建取 APPROVE 审批流水（content_review 单表，IModerationDao 内该语句零 JOIN） */
    private final IModerationDao moderationDao;

    // ---------- 商品 ----------

    @Override
    public void save(ProductEntity productEntity) {
        transactionTemplate.execute(status -> {
            // 业务 ID 落库前发号（uuid-biz-id 票 05）：随行一次写入，不再自增同值回写
            productEntity.setProductId(idGenerator.nextId());
            ProductPO productPO = toPO(productEntity);
            productDao.insert(productPO);
            // 入池点（spec §5.1）：提交落库即待审，工单开单与状态同事务（草稿行 DRAFT 不入池）
            if (productEntity.getStatus() == ProductStatusVO.PENDING_REVIEW) {
                reviewTaskPoolStore.enter(ReviewTargetTypeVO.PRODUCT, productEntity.getProductId(),
                        productEntity.getReviewRound(), productEntity.getUserId(), productEntity.getReviewEnteredAt());
            }
            return null;
        });
    }

    @Override
    public ProductEntity findById(String productId) {
        ProductPO row = productDao.selectById(productId);
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
                syncFeedIndexState(productId, ProductStatusVO.SOLD);
            }
            return null;
        });
    }

    @Override
    public void relist(String productId) {
        transactionTemplate.execute(status -> {
            if (productDao.relist(productId) == 0) {
                return null; // 条件 UPDATE 0 行由上层终态判定
            }
            // 入池点：重新上架回待审（SOLD→PENDING_REVIEW，轮次 +1）；读回本事务内新轮次/进入待审时刻开单
            enterPoolForPendingReview(productId);
            // 索引行同事务翻回不可见（行必存在：SOLD 前必经过 ON_SALE，行诞生于首次通过）
            syncFeedIndexState(productId, ProductStatusVO.PENDING_REVIEW);
            return null;
        });
    }

    @Override
    public void updateDraft(ProductEntity productEntity) {
        productDao.updateDraft(toPO(productEntity));
    }

    @Override
    public boolean submitDraft(String productId) {
        Boolean submitted = transactionTemplate.execute(status -> {
            boolean updated = productDao.submitDraft(productId) > 0;
            if (updated) {
                // 入池点：草稿提交进待审，轮次递增同事务可见；读回本事务内新值开单
                enterPoolForPendingReview(productId);
                // 索引行同事务不可见化：行存在（下架回草稿箱后重提）时翻 PENDING_REVIEW，
                // 从未通过审的草稿 0 行静默
                syncFeedIndexState(productId, ProductStatusVO.PENDING_REVIEW);
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
    public List<String> listReviewTimeoutIds(LocalDateTime before, int limit) {
        return productDao.selectReviewTimeoutIds(before, limit);
    }

    @Override
    public boolean withdraw(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean withdrawn = productDao.withdraw(productId) > 0;
            if (withdrawn) {
                // 退出待审（撤回回草稿箱）：在途工单出池停 DONE，防池中遗留指向非待审行的幽灵工单
                reviewTaskPoolStore.close(ReviewTargetTypeVO.PRODUCT, productId);
                // 索引行同事务不可见化：行不存在（从未通过审，撤回的常态）时 0 行静默
                syncFeedIndexState(productId, ProductStatusVO.DRAFT);
            }
            return withdrawn;
        }));
    }

    @Override
    public boolean rejectToDraft(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean updated = productDao.rejectToDraft(productId) > 0;
            if (updated) {
                // 被拒转草稿同事务索引行不可见化：行不存在（从未通过审）时 0 行静默
                syncFeedIndexState(productId, ProductStatusVO.DRAFT);
            }
            return updated;
        }));
    }

    @Override
    public boolean editOnSaleContent(ProductEntity productEntity) {
        Boolean edited = transactionTemplate.execute(status -> {
            // 手工组装编辑 PO（不用 toPO：未变更字段保持 null，动态 SQL 才不会覆盖）
            ProductPO productPO = new ProductPO();
            productPO.setProductId(productEntity.getProductId());
            productPO.setTitle(productEntity.getTitle());
            productPO.setDescription(productEntity.getDescription());
            productPO.setImages(productEntity.getImages() == null ? null : toImagesJson(productEntity.getImages()));
            productPO.setPrice(productEntity.getPrice());
            boolean updated = productDao.editOnSaleContent(productPO) > 0;
            if (updated) {
                // 入池点：在售改内容回待审（轮次递增同事务可见）；读回本事务内新值开单
                enterPoolForPendingReview(productEntity.getProductId());
                // 索引行同事务不可见化（行必存在：ON_SALE 才可改内容）；混提的新价不在此同步，
                // 恢复可见由 approve 语句的 price 快照兜住
                syncFeedIndexState(productEntity.getProductId(), ProductStatusVO.PENDING_REVIEW);
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
                if (productDao.updateFeedIndexPrice(productId, price) == 0) {
                    log.warn("[TradeRepository] 在售改价的索引旁路落 0 行：索引行缺失（存量异常），交重建 job 兜底 productId={}", productId);
                }
            }
            return updated;
        }));
    }

    @Override
    public boolean reviewApproveById(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean approved = productDao.reviewApproveById(productId) > 0;
            if (approved) {
                // feed 索引行诞生/翻回（同事务）：INSERT 侧落 price 快照与 feed_time（本次通过时刻），
                // 重提再通过经 ODKU 刷新 feed_time 与 price（进入当前对外可见轮的口径）
                productDao.upsertFeedIndexOnApprove(productId);
            }
            return approved;
        }));
    }

    @Override
    public boolean reviewRejectById(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean rejected = productDao.reviewRejectById(productId) > 0;
            if (rejected) {
                // 索引行同事务不可见化：从未通过审的商品 0 行静默（REJECTED 只可从待审可达）
                syncFeedIndexState(productId, ProductStatusVO.REJECTED);
            }
            return rejected;
        }));
    }

    @Override
    public boolean lock(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean locked = productDao.lock(productId) > 0;
            if (locked) {
                syncFeedIndexState(productId, ProductStatusVO.LOCKED);
            }
            return locked;
        }));
    }

    @Override
    public boolean release(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean released = productDao.release(productId) > 0;
            if (released) {
                // 取消订单释放回在售：索引行同事务翻回 ON_SALE（0 行不动索引——商品可能已被软删，
                // 无条件翻回会让已删商品在 feed 复活）
                syncFeedIndexState(productId, ProductStatusVO.ON_SALE);
            }
            return released;
        }));
    }

    @Override
    public boolean lockToSold(String productId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean sold = productDao.lockToSold(productId) > 0;
            if (sold) {
                syncFeedIndexState(productId, ProductStatusVO.SOLD);
            }
            return sold;
        }));
    }

    @Override
    public List<ProductEntity> pageList(ProductCategoryVO category, BigDecimal minPrice, BigDecimal maxPrice,
                                        String sort, int offset, int limit) {
        String categoryName = category == null ? null : category.name();
        // hot 档排序键 view_count 在窄表（SQL 不能 JOIN 进来）：ON_SALE 全集 + 窄表批量回查 + 内存排序切片。
        // 旧直查路径仅作降级（索引不可用时），数据集有界，可接受
        List<ProductPO> rows;
        if ("hot".equals(sort)) {
            List<ProductPO> allOnSale = productDao.selectOnSaleRows(categoryName, minPrice, maxPrice);
            Map<String, ProductPO> viewRows = viewCountRowsById(productDao.selectViewCountsByProductIds(
                    allOnSale.stream().map(ProductPO::getProductId).toList()));
            rows = allOnSale.stream()
                    .sorted(Comparator
                            .comparing((ProductPO p) -> viewCountOf(viewRows, p.getProductId())).reversed()
                            .thenComparing(ProductPO::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(ProductPO::getProductId, Comparator.reverseOrder()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        } else {
            rows = productDao.selectPage(categoryName, minPrice, maxPrice, sort, offset, limit);
        }
        fillProductExtras(rows);
        return rows.stream().map(this::toProductEntity).toList();
    }

    @Override
    public long count(ProductCategoryVO category, BigDecimal minPrice, BigDecimal maxPrice) {
        String categoryName = category == null ? null : category.name();
        return productDao.count(categoryName, minPrice, maxPrice);
    }

    @Override
    public List<ProductFeedIndexItemVO> scanFeedIndex(String sortType, ProductFeedCursorVO cursor, int limit) {
        return productDao.selectFeedIndex(sortType, cursor, limit);
    }

    /**
     * feed 索引全量重建（market-feed-uv 票 06，照 {@code CommunityRepository.rebuildFeedIndex} 先例）：
     * 四条语句一个事务——计数窄表校准（ODKU 补缺行 / 刷漂移 + 清孤儿）→ 清空索引 → 重灌投影。
     * 顺序是先校准后清灌：计数窄表是索引热度键的数据源，先算准再抄进重灌语句，一个事务里两次写只对
     * 读者呈现前后两个完整状态（清空用 DELETE 不用 TRUNCATE，后者隐式提交会毁掉原子性）
     */
    @Override
    public void rebuildFeedIndex() {
        transactionTemplate.executeWithoutResult(status -> {
            int recalibrated = productDao.recalibrateViewCounts();
            int orphans = productDao.deleteViewCountsMissingHistory();
            productDao.clearFeedIndex();
            int rebuilt = rebuildFeedIndexRows();
            log.info("[MarketFeedIndexRebuild] product_feed_index 清空重灌 {} 行；product_view_count 校准 {} 行、清理孤儿 {} 行",
                    rebuilt, recalibrated, orphans);
        });
    }

    /**
     * 重灌行集拼装（原 INSERT..SELECT 一步投影的多查形态）：行集 = ON_SALE 商品 ∪ 有过审批通过的商品。
     * 三段取数——① product 全量 (product_id, state, create_time, price)；② content_review 的 APPROVE 流水
     * （feed_time 真源，经 IModerationDao 单表 GROUP BY，查不到审批流水取 create_time 兜底）；
     * ③ view_count 从计数窄表抄入（无行 = 0）——组装后批量写回
     */
    private int rebuildFeedIndexRows() {
        Map<String, LocalDateTime> approveTimes = new HashMap<>();
        for (ContentReviewPO approveRow : moderationDao.selectApproveRowsByTargetType(ReviewTargetTypeVO.PRODUCT.name())) {
            approveTimes.put(approveRow.getTargetId(), approveRow.getCreateTime());
        }
        List<ProductFeedIndexRowPO> rows = new ArrayList<>();
        for (ProductFeedIndexRowPO candidate : productDao.selectRebuildRows()) {
            boolean approved = approveTimes.containsKey(candidate.getProductId());
            if (!ProductStatusVO.ON_SALE.name().equals(candidate.getState()) && !approved) {
                // 从未通过审的待审/驳回/草稿商品不凭空进索引
                continue;
            }
            candidate.setFeedTime(approveTimes.getOrDefault(candidate.getProductId(), candidate.getCreateTime()));
            rows.add(candidate);
        }
        if (!rows.isEmpty()) {
            Map<String, ProductPO> viewRows = viewCountRowsById(productDao.selectViewCountsByProductIds(
                    rows.stream().map(ProductFeedIndexRowPO::getProductId).toList()));
            for (ProductFeedIndexRowPO row : rows) {
                row.setViewCount(viewCountOf(viewRows, row.getProductId()));
            }
            for (int from = 0; from < rows.size(); from += REBUILD_INSERT_BATCH) {
                productDao.insertFeedIndexRows(rows.subList(from, Math.min(from + REBUILD_INSERT_BATCH, rows.size())));
            }
        }
        return rows.size();
    }

    @Override
    public boolean addFavorite(String productId, String userId) {
        return productDao.insertFavorite(productId, userId) > 0;
    }

    @Override
    public boolean removeFavorite(String productId, String userId) {
        return productDao.deleteFavorite(productId, userId) > 0;
    }

    @Override
    public boolean isFavorited(String productId, String userId) {
        return productDao.existsFavorite(productId, userId) > 0;
    }

    @Override
    public long countFavorites(String productId) {
        return productDao.countFavorites(productId);
    }

    @Override
    public List<ProductEntity> listFavorites(String userId, int offset, int limit) {
        // 收藏关系行主查 → 商品行两段查（原 INNER JOIN product 的行集与 state != 'DELETED' 谓词）
        List<String> favoriteIds = productDao.selectFavoriteProductIdsByUser(userId);
        if (favoriteIds.isEmpty()) {
            return List.of();
        }
        Map<String, ProductPO> productsById = productDao.selectByIds(favoriteIds).stream()
                .collect(Collectors.toMap(ProductPO::getProductId, product -> product, (a, b) -> a));
        List<ProductPO> visibleRows = new ArrayList<>(favoriteIds.size());
        for (String productId : favoriteIds) {
            ProductPO product = productsById.get(productId);
            if (product != null && !ProductStatusVO.DELETED.name().equals(product.getState())) {
                visibleRows.add(product);
            }
        }
        List<ProductPO> page = visibleRows.stream().skip(offset).limit(limit).toList();
        fillProductExtras(page);
        return page.stream().map(this::toProductEntity).toList();
    }

    @Override
    public List<ProductEntity> pageBySeller(String userId, int offset, int limit) {
        List<ProductPO> rows = productDao.selectPageBySeller(userId, offset, limit);
        fillProductExtras(rows);
        return rows.stream().map(this::toProductEntity).toList();
    }

    @Override
    public long countBySeller(String userId) {
        return productDao.countBySeller(userId);
    }

    @Override
    public List<ProductEntity> pageOnSaleByUser(String userId, int offset, int limit) {
        List<ProductPO> rows = productDao.selectOnSalePageByUser(userId, offset, limit);
        fillProductExtras(rows);
        return rows.stream().map(this::toProductEntity).toList();
    }

    @Override
    public long countOnSaleByUser(String userId) {
        return productDao.countOnSaleByUser(userId);
    }

    @Override
    public void recordProductView(String productId, String userId) {
        // 浏览计数（UV 口径）：入队即返回，窗口过后一组一个事务——明细表 INSERT IGNORE + viewed_at
        // 刷新 + 加速列 += 新增数。匿名访问落不了明细行（product_view_history 主键的一半是 user_id），
        // 按「INSERT 成功才算浏览」的口径不计入（商品语义变更：旧口径匿名也累加 PV）；
        // 明细行即真相源，失败只记丢弃数（spec §1.2 / §3.2）
        if (userId == null) {
            return;
        }
        // 商品业务 ID 已是 UUID 字符串（uuid-biz-id 票 05）：合并器键直传
        viewMergeWriter.write(CountAction.PRODUCT_VIEW, productId, userId);
    }

    @Override
    public List<ProductEntity> listByIds(List<String> productIds) {
        List<ProductPO> rows = productDao.selectByIds(productIds);
        fillProductExtras(rows);
        return rows.stream().map(this::toProductEntity).toList();
    }

    @Override
    public List<String> selectViewedProductIds(String userId, List<String> productIds) {
        if (userId == null || productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productDao.selectViewedProductIds(userId, productIds);
    }

    @Override
    public List<String> listIdsAfter(String lastProductId, int limit) {
        return productDao.selectIdsAfter(lastProductId, limit);
    }

    @Override
    public void saveSearchSyncMessage(String productId) {
        outboxMessageStore.save(Constants.SEARCH_SYNC_TOPIC,
                new SearchSyncMessage(SearchEntityType.PRODUCT.name().toLowerCase(), productId));
    }

    // ---------- 计数合并器的落库方法：商品浏览（UV 口径，market-feed-uv 票 02） ----------

    /**
     * 一组商品浏览（{@link com.betterschool.infrastructure.adapter.repository.community.CommunityRepository}
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
     * 不重试、不回补：浏览允许丢，丢了下次浏览的 INSERT 会把缺的 UV 补上。
     *
     * <p><b>为什么要回一组占位结果</b>：浏览件没有业务结果（没有人在等），但引擎把「null / 缺位」
     * 当作漏回填并补 {@code FAILED}——那会让 {@code flushed} 漏掉这些件，合并比与
     * 「accepted − flushed」两个读数同时失真。
     *
     * <p><b>不再写主表列</b>：{@code product.view_count} 旧 PV 列随本票停写（读路径已换源
     * {@code product_view_count}），删列随收口票 06。
     */
    public List<LikeState> flushViewGroup(String productId, List<CountEvent> events) {
        // 合并器键即商品业务 ID 字符串（UUID，uuid-biz-id 票 05 起直传，过渡期的 parseLong 缝合已拆）
        return transactionTemplate.execute(status -> {
            List<String> userIds = distinctUserIds(events);
            int inserted = productDao.insertProductViewHistory(productId, userIds);
            productDao.touchProductViewedAt(productId, userIds);
            if (inserted > 0) {
                productDao.upsertProductViewCount(productId, inserted);
                // 热度排序键旁路 ±N（market-feed-uv 票 03）：索引行 view_count 同事务跟上
                syncFeedIndexViewCount(productId, inserted);
            }
            return Collections.nCopies(events.size(), LikeState.APPLIED);
        });
    }

    /**
     * feed 索引行状态快照（market-feed-uv 票 03）：条件 UPDATE 命中后同事务镜像 product 的新状态。
     * 纯 UPDATE 不 upsert——行不存在的常态（从未通过审的待审/驳回/草稿/草稿软删）0 行静默，
     * 不在写路径凭空补行
     */
    private void syncFeedIndexState(String productId, ProductStatusVO state) {
        productDao.updateFeedIndexState(productId, state.name());
    }

    /**
     * feed 索引行热度键旁路 ±N（浏览没有取消语义，恒 +）：纯 UPDATE 不 upsert。命中 0 行 =
     * 索引行缺失（未过审商品被本人浏览、或存量异常），落 warn 交重建 job（票 06），不在写路径补行
     */
    private void syncFeedIndexViewCount(String productId, int delta) {
        if (productDao.updateFeedIndexViewCount(productId, delta) == 0) {
            log.warn("[TradeRepository] 商品浏览旁路落 0 行：索引行缺失（未过审商品的浏览或存量异常），交重建 job 兜底 productId={}", productId);
        }
    }

    /** 一组内的去重浏览者（同组同人是重复件，明细表主键也只收得下一行） */
    private List<String> distinctUserIds(List<CountEvent> events) {
        return events.stream().map(CountEvent::userId).distinct().toList();
    }

    // ---------- 商品托管订单 ----------

    @Override
    public void saveOrder(ProductOrderEntity orderEntity) {
        // 业务 ID 落库前发号（uuid-biz-id 票 05）：随行一次写入，不再自增同值回写
        orderEntity.setOrderId(idGenerator.nextId());
        ProductOrderPO orderPO = toPO(orderEntity);
        productOrderDao.insert(orderPO);
        // 创建事件：from_state 为 NULL（sql.md 口径），操作方恒买家
        saveEvent(orderPO.getProductOrderId(), "CREATED", null, orderPO.getState(),
                orderPO.getBuyerId(), "BUYER");
    }

    @Override
    public ProductOrderEntity findOrderById(String orderId) {
        ProductOrderPO row = productOrderDao.selectById(orderId);
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
        ProductOrderPO prior = productOrderDao.selectById(orderId);
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
        List<ProductOrderPO> rows = productOrderDao.selectMineByBuyer(userId, offset, limit);
        fillOrderUserExtras(rows);
        return rows.stream().map(this::toOrderEntity).toList();
    }

    @Override
    public long countMineByBuyer(String userId) {
        return productOrderDao.countMineByBuyer(userId);
    }

    @Override
    public List<ProductOrderEntity> listMineBySeller(String userId, int offset, int limit) {
        List<ProductOrderPO> rows = productOrderDao.selectMineBySeller(userId, offset, limit);
        fillOrderUserExtras(rows);
        return rows.stream().map(this::toOrderEntity).toList();
    }

    @Override
    public long countMineBySeller(String userId) {
        return productOrderDao.countMineBySeller(userId);
    }

    @Override
    public void schedulePayTimeout(String orderId, LocalDateTime actionTime) {
        // 业务事务内直插超时任务行（内嵌超时中心，注册 outbox 已删）：与业务写同一条连接同一事务，
        // 回滚即无行、提交即被扫描可见；同键重入的三分支幂等（新插入/在途不改期/终态复活）在仓储内裁决
        timeoutRepository.register(TimeoutTaskEntity.newWaiting(
                Constants.PRODUCT_ORDER_PAY_TIMEOUT, orderId, actionTime, LocalDateTime.now()));
    }

    // ---------- 跨商品与订单的原子用例 ----------

    @Override
    public String createOrderWithLock(ProductOrderEntity orderEntity, String productId, int payTimeoutMinutes) {
        transactionTemplate.execute(status -> {
            saveOrder(orderEntity);
            if (!lock(productId)) {
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品已被交易，快去逛逛别的吧");
            }
            // 锁定后商品退出搜索，落同步消息（消费方反查 LOCKED 删文档）
            saveSearchSyncMessage(productId);
            // "15 分钟未支付"超时任务与下单同事务直插 timeout_task（内嵌超时中心）：
            // 到期时刻在下单事务内算（与改造前同口径）
            schedulePayTimeout(orderEntity.getOrderId(), LocalDateTime.now().plusMinutes(payTimeoutMinutes));
            return null;
        });
        return orderEntity.getOrderId();
    }

    @Override
    public boolean payOrderWithSearchSync(String orderId, String buyerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!payOrderById(orderId, buyerId)) {
                return false;
            }
            // 支付后商品 LOCKED → SOLD，落搜索同步消息（保持文档不存在）
            saveSearchSyncMessage(findOrderById(orderId).getProductId());
            return true;
        }));
    }

    @Override
    public boolean confirmOrderWithSearchSync(String orderId, String buyerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!confirmOrderById(orderId, buyerId)) {
                return false;
            }
            // 确认收货即交易完成：商品 LOCKED → SOLD（幂等，0 行无妨），落搜索同步消息保持文档不存在
            String productId = findOrderById(orderId).getProductId();
            lockToSold(productId);
            saveSearchSyncMessage(productId);
            return true;
        }));
    }

    @Override
    public boolean cancelOrderWithSearchSync(String orderId, String buyerId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!cancelOrderById(orderId, buyerId)) {
                return false;
            }
            String productId = findOrderById(orderId).getProductId();
            // 商品锁定释放回在售，落搜索同步消息（重新可搜）
            if (!release(productId)) {
                // 商品已被卖家软删/其他终态：release 0 行无妨，同步消息兜底删除/幂等
                log.warn("[ProductOrder] 取消订单释放商品失败，可能已被删除 productId={}", productId);
            }
            saveSearchSyncMessage(productId);
            return true;
        }));
    }

    @Override
    public boolean cancelPendingPaymentWithSearchSync(String orderId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!cancelPendingPaymentOrder(orderId)) {
                return false;
            }
            String productId = findOrderById(orderId).getProductId();
            release(productId);
            saveSearchSyncMessage(productId);
            return true;
        }));
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
            // 改内容回待审：期间他人不可见，同事务落搜索同步消息，消费方按状态删文档
            saveSearchSyncMessage(productEntity.getProductId());
            return null;
        });
    }

    @Override
    public void reviewApproveWithSearchSync(String productId) {
        transactionTemplate.execute(status -> {
            if (!reviewApproveById(productId)) {
                // 0 行即抛，在事务内回滚（异常点与改造前一致，不在域内抛）
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
            }
            // 先审后售：审核通过转 ON_SALE 时落搜索同步消息（进入对外可见态，消费方按状态 upsert）
            saveSearchSyncMessage(productId);
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
            // 待审商品可被卖家删除：退出待审同事务出池（其余状态无在途工单，幂等 0 行无害）
            reviewTaskPoolStore.close(ReviewTargetTypeVO.PRODUCT, productId);
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
     * 商品行补齐浏览 UV 加速列 + 卖家昵称头像：product_view_count 一次 IN 批量回查、bs_user 一次 IN 批量回查。
     * 缺行补 0 / 补 null（与原 LEFT JOIN 合成口径一致）；列表与详情共用
     */
    private void fillProductExtras(List<ProductPO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, ProductPO> viewRows = viewCountRowsById(
                productDao.selectViewCountsByProductIds(rows.stream().map(ProductPO::getProductId).toList()));
        List<String> userIds = rows.stream().map(ProductPO::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<String, UserPO> users = userIds.isEmpty() ? Map.of() : userDao.selectByIds(userIds).stream()
                .collect(Collectors.toMap(UserPO::getUserId, user -> user, (a, b) -> a));
        for (ProductPO row : rows) {
            row.setViewCount(viewCountOf(viewRows, row.getProductId()));
            UserPO user = users.get(row.getUserId());
            if (user != null) {
                row.setSellerNickname(user.getNickname());
                row.setSellerAvatar(user.getAvatar());
            }
        }
    }

    /** 部分列计数行 → productId 索引 */
    private static Map<String, ProductPO> viewCountRowsById(List<ProductPO> viewRows) {
        return viewRows.stream().collect(Collectors.toMap(ProductPO::getProductId, row -> row, (a, b) -> a));
    }

    /** 窄表缺行 = 计数 0（与原 LEFT JOIN IFNULL(x, 0) 同口径） */
    private static Integer viewCountOf(Map<String, ProductPO> viewRows, String productId) {
        ProductPO row = viewRows.get(productId);
        return row == null ? 0 : row.getViewCount();
    }

    /**
     * 订单行补齐买卖双方昵称头像（原 SELECT 里 bs_user ×2 的 LEFT JOIN 列）：
     * 买卖双方 userId 合并一次 bs_user 批量回查，缺行补 null（与 LEFT JOIN 一致）
     */
    private void fillOrderUserExtras(List<ProductOrderPO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> userIds = new ArrayList<>(rows.size() * 2);
        for (ProductOrderPO row : rows) {
            userIds.add(row.getBuyerId());
            userIds.add(row.getSellerId());
        }
        Map<String, UserPO> users = userDao.selectByIds(
                userIds.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(UserPO::getUserId, user -> user, (a, b) -> a));
        for (ProductOrderPO row : rows) {
            UserPO buyer = users.get(row.getBuyerId());
            UserPO seller = users.get(row.getSellerId());
            row.setBuyerNickname(buyer == null ? null : buyer.getNickname());
            row.setBuyerAvatar(buyer == null ? null : buyer.getAvatar());
            row.setSellerNickname(seller == null ? null : seller.getNickname());
            row.setSellerAvatar(seller == null ? null : seller.getAvatar());
        }
    }

    // ---------- PO / Entity 转换 ----------

    /**
     * 商品入池开单（须处于状态迁移的同事务内）：读回刚翻转为 PENDING_REVIEW 的行的
     * 轮次 / 进入待审时刻 / 作者，交 ReviewTaskPoolStore 幂等开单（uk_target_round 吸收重复）
     */
    private void enterPoolForPendingReview(String productId) {
        ProductPO pending = productDao.selectById(productId);
        reviewTaskPoolStore.enter(ReviewTargetTypeVO.PRODUCT, productId,
                pending.getReviewRound() == null ? 1 : pending.getReviewRound(),
                pending.getUserId(), pending.getReviewEnteredAt());
    }

    private ProductPO toPO(ProductEntity productEntity) {
        ProductPO productPO = new ProductPO();
        productPO.setProductId(productEntity.getProductId());
        productPO.setUserId(productEntity.getUserId());
        productPO.setTitle(productEntity.getTitle());
        productPO.setDescription(productEntity.getDescription());
        productPO.setImages(toImagesJson(productEntity.getImages()));
        productPO.setPrice(productEntity.getPrice());
        // 草稿行可空：tradeLocation/category 未填时存 NULL，提交时才要求必填
        productPO.setTradeLocation(productEntity.getTradeLocation());
        productPO.setCategory(productEntity.getCategory() == null ? null : productEntity.getCategory().name());
        productPO.setState(productEntity.getStatus().name());
        productPO.setReviewRound(productEntity.getReviewRound());
        productPO.setReviewEnteredAt(productEntity.getReviewEnteredAt());
        productPO.setViewCount((int) productEntity.getViewCount());
        productPO.setCreateTime(productEntity.getCreatedAt());
        productPO.setUpdateTime(productEntity.getCreatedAt());
        productPO.setExpireAt(productEntity.getExpireAt());
        return productPO;
    }

    private ProductEntity toProductEntity(ProductPO productPO) {
        if (productPO == null) {
            return null;
        }
        ProductEntity productEntity = new ProductEntity();
        productEntity.setProductId(productPO.getProductId());
        productEntity.setUserId(productPO.getUserId());
        productEntity.setTitle(productPO.getTitle());
        productEntity.setDescription(productPO.getDescription());
        productEntity.setImages(toImagesList(productPO.getImages()));
        productEntity.setPrice(productPO.getPrice());
        productEntity.setTradeLocation(productPO.getTradeLocation());
        productEntity.setCategory(productPO.getCategory() == null ? null : ProductCategoryVO.valueOf(productPO.getCategory()));
        productEntity.setStatus(ProductStatusVO.valueOf(productPO.getState()));
        productEntity.setReviewRound(productPO.getReviewRound() == null ? 0 : productPO.getReviewRound());
        productEntity.setReviewEnteredAt(productPO.getReviewEnteredAt());
        productEntity.setViewCount(productPO.getViewCount());
        productEntity.setCreatedAt(productPO.getCreateTime());
        productEntity.setExpireAt(productPO.getExpireAt());
        productEntity.setSellerNickname(productPO.getSellerNickname());
        productEntity.setSellerAvatar(productPO.getSellerAvatar());
        return productEntity;
    }

    private ProductOrderPO toPO(ProductOrderEntity orderEntity) {
        ProductOrderPO orderPO = new ProductOrderPO();
        orderPO.setProductOrderId(orderEntity.getOrderId());
        orderPO.setBuyerId(orderEntity.getBuyerId());
        orderPO.setSellerId(orderEntity.getSellerId());
        orderPO.setProductId(orderEntity.getProductId());
        orderPO.setPrice(orderEntity.getPrice());
        orderPO.setProductTitle(orderEntity.getProductTitle());
        orderPO.setProductDescription(orderEntity.getProductDescription());
        orderPO.setProductImages(toOrderImagesJson(orderEntity.getProductImages()));
        orderPO.setProductTradeLocation(orderEntity.getProductTradeLocation());
        orderPO.setProductCategory(orderEntity.getProductCategory() == null ? null : orderEntity.getProductCategory().name());
        orderPO.setState(orderEntity.getStatus().name());
        orderPO.setCreateTime(orderEntity.getCreatedAt());
        orderPO.setUpdateTime(orderEntity.getUpdatedAt());
        return orderPO;
    }

    private ProductOrderEntity toOrderEntity(ProductOrderPO orderPO) {
        if (orderPO == null) {
            return null;
        }
        ProductOrderEntity orderEntity = new ProductOrderEntity();
        orderEntity.setOrderId(orderPO.getProductOrderId());
        orderEntity.setBuyerId(orderPO.getBuyerId());
        orderEntity.setSellerId(orderPO.getSellerId());
        orderEntity.setProductId(orderPO.getProductId());
        orderEntity.setPrice(orderPO.getPrice());
        orderEntity.setProductTitle(orderPO.getProductTitle());
        orderEntity.setProductDescription(orderPO.getProductDescription());
        orderEntity.setProductImages(toOrderImagesList(orderPO.getProductImages()));
        orderEntity.setProductTradeLocation(orderPO.getProductTradeLocation());
        orderEntity.setProductCategory(orderPO.getProductCategory() == null ? null : ProductCategoryVO.valueOf(orderPO.getProductCategory()));
        orderEntity.setStatus(ProductOrderStatusVO.valueOf(orderPO.getState()));
        orderEntity.setCreatedAt(orderPO.getCreateTime());
        orderEntity.setUpdatedAt(orderPO.getUpdateTime());
        orderEntity.setBuyerNickname(orderPO.getBuyerNickname());
        orderEntity.setBuyerAvatar(orderPO.getBuyerAvatar());
        orderEntity.setSellerNickname(orderPO.getSellerNickname());
        orderEntity.setSellerAvatar(orderPO.getSellerAvatar());
        orderEntity.setProductImage(orderPO.getProductImage());
        return orderEntity;
    }

    private String toImagesJson(List<String> images) {
        try {
            return objectMapper.writeValueAsString(images);
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
     * 空列表落 SQL NULL（列可空，见 docs/db/trade.md）；失败抛 FrameworkException。
     */
    private String toOrderImagesJson(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(images);
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
        LocalDateTime now = LocalDateTime.now();
        ProductOrderEventPO eventPO = new ProductOrderEventPO();
        eventPO.setOrderId(orderId);
        eventPO.setEventType(eventType);
        eventPO.setFromState(fromState);
        eventPO.setToState(toState);
        eventPO.setActorId(actorId);
        eventPO.setActorRole(actorRole);
        eventPO.setReasonType(reasonType);
        eventPO.setCreateTime(now);
        eventPO.setUpdateTime(now);
        productOrderDao.insertOrderEvent(eventPO);
    }
}
