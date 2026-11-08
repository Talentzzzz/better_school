import re

def edit(p, pairs, optional=()):
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (p, old[:80])
        s = s.replace(old, new)
    for old, new in optional:
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print("ok", p)

def cut_method(src, signature):
    idx = src.index(signature)
    start = src.rindex('    @Override', 0, idx)
    m = re.search(r'(/\*\*[\s\S]*?\*/\n)(    @Override\n)?' + re.escape(signature), src)
    if m:
        start = m.start(1)
    nxt = re.search(r'\n    (?:@Override|/\*\*|private|public|static|final|// -)', src[idx:])
    end = idx + nxt.start() + 1
    return src[:start] + src[end:], src[start:end]

def drop(sig):
    global s
    s, removed = cut_method(s, sig)
    print("dropped:", sig.strip()[:60], f"({len(removed)} chars)")

def rep(old, new):
    global s
    assert old in s, old[:80]
    s = s.replace(old, new)

# ============ ITradeRepository ============
edit('bs-domain/src/main/java/com/betterschool/domain/trade/adapter/repository/ITradeRepository.java', [
("""    /** 提交草稿：DRAFT -> PENDING_REVIEW，返回 false = 状态已变化 */
    boolean submitDraft(String productId);

    /** 撤回待审：PENDING_REVIEW -> DRAFT，返回 false = 状态已变化 */
    boolean withdraw(String productId);

    /** 被拒转草稿箱：REJECTED -> DRAFT，返回 false = 状态已变化 */
    boolean rejectToDraft(String productId);

    /** 上架后改内容：ON_SALE -> PENDING_REVIEW（轮次 +1，仅非 null 字段覆盖），返回 false = 状态已变化 */
    boolean editOnSaleContent(ProductEntity productEntity);""",
"""    /** 提交草稿：DRAFT -> ON_SALE，返回 false = 状态已变化 */
    boolean submitDraft(String productId);

    /** 上架后改内容：仅非 null 字段覆盖、状态不变（立即生效），返回 false = 状态已变化 */
    boolean editOnSaleContent(ProductEntity productEntity);"""),
("""    /** 24h 提醒扫描：PENDING_REVIEW 且 review_entered_at 早于 before 的商品 id 列表 */
    List<String> listReviewTimeoutIds(LocalDateTime before, int limit);

    /** 审核通过：PENDING_REVIEW -> ON_SALE，返回 false = 状态已变化 */
    boolean reviewApproveById(String productId);

    /** 审核拒绝：PENDING_REVIEW -> REJECTED，返回 false = 状态已变化 */
    boolean reviewRejectById(String productId);

    /** 软删：status -> DELETED */""",
"""    /** 软删：status -> DELETED */"""),
("""    /** 重新上架：SOLD -> ON_SALE */
    void relist(String productId);""",
"""    /** 重新上架：SOLD -> ON_SALE（同事务索引行翻回 + 搜索同步消息） */
    void relist(String productId);"""),
("""    /** 上架后改内容回待审：条件 UPDATE（轮次 +1）+ 搜索同步消息，同一事务；0 行即抛 BUSINESS_ERROR（提交前回滚） */
    void editOnSaleContentWithSearchSync(ProductEntity productEntity);

    /** 审核通过：条件 UPDATE PENDING_REVIEW → ON_SALE + 搜索同步消息，同一事务；0 行即抛 BUSINESS_ERROR（提交前回滚） */
    void reviewApproveWithSearchSync(String productId);
""",
"""    /** 上架后改内容（立即生效，价格快照同事务跟索引）：+ 搜索同步消息，同一事务；0 行即抛 BUSINESS_ERROR（提交前回滚） */
    void editOnSaleContentWithSearchSync(ProductEntity productEntity);
"""),
])

# ============ ProductServiceImpl ============
edit('bs-domain/src/main/java/com/betterschool/domain/trade/service/product/ProductServiceImpl.java', [
("""        // 先审后售：进入待审，审核通过转 ON_SALE 时才落搜索同步消息
        tradeRepository.save(productEntity);""",
"""        // 发布即可见：落库 + feed 索引行诞生 + 搜索同步消息同事务（实现内）
        tradeRepository.save(productEntity);"""),
("        // 草稿为私有停靠内容：不做初筛、不做信用分门槛（提交时才校验），字段可缺省",
 "        // 草稿为私有停靠内容：不做信用分门槛（提交时才校验），字段可缺省"),
("""        if (productEntity.getStatus() != ProductStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可编辑，待审中的商品可先撤回再修改");
        }""",
"""        if (productEntity.getStatus() != ProductStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可编辑");
        }"""),
("""    @Override
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
""",
"""    @Override
    public void submitProductDraft(String userId, String productId) {
        // 完整性校验由 case 层前置
        getOwnedProductDraft(userId, productId);
        if (!tradeRepository.submitDraft(productId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
    }
"""),
("""    @Override
    public List<String> listReviewTimeoutProductIds() {
        // PENDING_REVIEW 由提交/编辑条件 UPDATE 翻转而来，update_time 即进入待审时间
        LocalDateTime cutoff = LocalDateTime.now().minusHours(REVIEW_REMINDER_HOURS);
        return tradeRepository.listReviewTimeoutIds(cutoff, SCAN_BATCH_SIZE);
    }

""", ""),
("""        // 编辑语义守卫：待审中只能撤回转草稿；被拒走草稿箱自救路径；其余非在售状态不可编辑
        if (productEntity.getStatus() == ProductStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "待审中不可编辑，可先撤回转草稿后修改");
        }
        if (productEntity.getStatus() == ProductStatusVO.REJECTED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品已被拒绝，可转入草稿箱修改后重新提交");
        }
        if (productEntity.getStatus() != ProductStatusVO.ON_SALE) {""",
"""        // 编辑语义守卫：仅 ON_SALE 可编辑
        if (productEntity.getStatus() != ProductStatusVO.ON_SALE) {"""),
("""        if (contentChanged) {
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
""",
"""        if (contentChanged) {
            ProductEntity editEntity = new ProductEntity();
            editEntity.setProductId(productId);
            editEntity.setTitle(title);
            editEntity.setDescription(description);
            editEntity.setImages(images);
            editEntity.setPrice(price);
            // 改内容立即生效（状态不变），同事务落搜索同步消息（消费方按状态 upsert）
            tradeRepository.editOnSaleContentWithSearchSync(editEntity);
            return;
        }
        // 仅改价：立即生效（价格不进搜索文档内容，但价格快照列同事务跟索引）
        if (!tradeRepository.updateOnSalePrice(productId, price)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
        }
    }
"""),
], optional=[
    ("    private static final int REVIEW_REMINDER_HOURS = 24;\n", ""),
])

# ============ TradeRepository ============
P = 'bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/repository/trade/TradeRepository.java'
s = open(P, encoding='utf-8').read()

rep("import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;\n", "")
rep("import com.betterschool.infrastructure.adapter.repository.moderation.ReviewTaskPoolStore;\n", "")
rep("""    /** feed 索引重建取 APPROVE 审批流水（content_review 单表，IContentReviewDao 内该语句零 JOIN） */
    private final IContentReviewDao contentReviewDao;
""", "")

rep("""            productDao.insert(product);
            // 入池点（spec §5.1）：提交落库即待审，工单开单与状态同事务（草稿行 DRAFT 不入池）；
            // entered_at 由开单 SQL NOW() 落库（时刻一律数据库时钟，≈发布时刻，同事务同秒）
            if (productEntity.getStatus() == ProductStatusVO.PENDING_REVIEW) {
                reviewTaskPoolStore.enter(ReviewTargetTypeVO.PRODUCT, productEntity.getProductId(),
                        productEntity.getReviewRound(), productEntity.getUserId());
            }
            return null;""",
"""            productDao.insert(product);
            if (productEntity.getStatus() == ProductStatusVO.ON_SALE) {
                // 发布即可见：feed 索引行诞生 + 搜索同步消息，与落库同一事务
                productFeedIndexDao.upsertFeedIndexOnApprove(productEntity.getProductId());
                saveSearchSyncMessage(productEntity.getProductId());
            }
            return null;""")

rep("""            if (productDao.relist(productId) == 0) {
                return null; // 条件 UPDATE 0 行由上层终态判定
            }
            // 入池点：重新上架回待审（SOLD→PENDING_REVIEW，轮次 +1）；读回本事务内新轮次/进入待审时刻开单
            enterPoolForPendingReview(productId);
            // 索引行同事务翻回不可见（行必存在：SOLD 前必经过 ON_SALE，行诞生于首次通过）
            syncFeedIndexState(productId, ProductStatusVO.PENDING_REVIEW);
            return null;""",
"""            if (productDao.relist(productId) == 0) {
                return null; // 条件 UPDATE 0 行由上层终态判定
            }
            // 索引行同事务翻回可见（行必存在：SOLD 前必经过 ON_SALE）
            syncFeedIndexState(productId, ProductStatusVO.ON_SALE);
            // 重新对外可见，落搜索同步消息（消费方按状态 upsert）
            saveSearchSyncMessage(productId);
            return null;""")

rep("""            boolean updated = productDao.submitDraft(productId) > 0;
            if (updated) {
                // 入池点：草稿提交进待审，轮次递增同事务可见；读回本事务内新值开单
                enterPoolForPendingReview(productId);
                // 索引行同事务不可见化：行存在（下架回草稿箱后重提）时翻 PENDING_REVIEW，
                // 从未通过审的草稿 0 行静默
                syncFeedIndexState(productId, ProductStatusVO.PENDING_REVIEW);
            }
            return updated;""",
"""            boolean updated = productDao.submitDraft(productId) > 0;
            if (updated) {
                // 提交即上架可见：feed 索引行诞生/翻回 + 搜索同步消息，同一事务
                productFeedIndexDao.upsertFeedIndexOnApprove(productId);
                saveSearchSyncMessage(productId);
            }
            return updated;""")

for sig in [
    'public boolean withdraw(String productId)',
    'public boolean rejectToDraft(String productId)',
    'public List<String> listReviewTimeoutIds(LocalDateTime before, int limit)',
    'public boolean reviewApproveById(String productId)',
    'public boolean reviewRejectById(String productId)',
    'public void reviewApproveWithSearchSync(String productId)',
]:
    s, removed = cut_method(s, sig)
    print("dropped:", sig.strip()[:60], f"({len(removed)} chars)")

rep("""            boolean updated = productDao.editOnSaleContent(product) > 0;
            if (updated) {
                // 入池点：在售改内容回待审（轮次递增同事务可见）；读回本事务内新值开单
                enterPoolForPendingReview(productEntity.getProductId());
                // 索引行同事务不可见化（行必存在：ON_SALE 才可改内容）；混提的新价不在此同步，
                // 恢复可见由 approve 语句的 price 快照兜住
                syncFeedIndexState(productEntity.getProductId(), ProductStatusVO.PENDING_REVIEW);
            }
            return updated;""",
"""            boolean updated = productDao.editOnSaleContent(product) > 0;
            if (updated) {
                // 改内容立即生效（状态不变，行必存在：ON_SALE 才可改内容）；
                // 混提的新价同事务刷新索引 price 快照，防价格排序漂移
                if (productEntity.getPrice() != null
                        && productFeedIndexDao.updateFeedIndexPrice(productEntity.getProductId(), productEntity.getPrice()) == 0) {
                    log.warn("[TradeRepository] 在售改内容的索引价格旁路落 0 行：索引行缺失（存量异常），交重建 job 兜底 productId={}",
                            productEntity.getProductId());
                }
            }
            return updated;""")

rep("""    public void editOnSaleContentWithSearchSync(ProductEntity productEntity) {
        transactionTemplate.execute(status -> {
            if (!editOnSaleContent(productEntity)) {
                // 0 行即抛，在事务内回滚（异常点与改造前一致，不在域内抛）
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
            }
            // 改内容回待审：期间他人不可见，同事务落搜索同步消息，消费方按状态删文档
            saveSearchSyncMessage(productEntity.getProductId());
            return null;
        });
    }""",
"""    public void editOnSaleContentWithSearchSync(ProductEntity productEntity) {
        transactionTemplate.execute(status -> {
            if (!editOnSaleContent(productEntity)) {
                // 0 行即抛，在事务内回滚（异常点与改造前一致，不在域内抛）
                throw new BusinessException(ResponseCode.BUSINESS_ERROR, "商品状态已变化，请刷新后重试");
            }
            // 改内容立即生效，同事务落搜索同步消息（消费方按状态 upsert）
            saveSearchSyncMessage(productEntity.getProductId());
            return null;
        });
    }""")

rep("""            // 在售商品对外可见过，软删后需同步删除搜索文档；草稿从未对外可见，无需同步
            if (publiclyVisible) {
                saveSearchSyncMessage(productId);
            }
            // 待审商品可被卖家删除：退出待审同事务出池（其余状态无在途工单，幂等 0 行无害）
            reviewTaskPoolStore.close(ReviewTargetTypeVO.PRODUCT, productId);
            return null;""",
"""            // 在售商品对外可见过，软删后需同步删除搜索文档；草稿从未对外可见，无需同步
            if (publiclyVisible) {
                saveSearchSyncMessage(productId);
            }
            return null;""")

rep("""    /**
     * 重灌行集拼装（原 INSERT..SELECT 一步投影的多查形态）：行集 = ON_SALE 商品 ∪ 有过审批通过的商品。
     * 三段取数——① product 全量 (product_id, state, create_time, price)；② content_review 的 APPROVE 流水
     * （feed_time 真源，经 IContentReviewDao 单表 GROUP BY，查不到审批流水取 create_time 兜底）；
     * ③ view_count 从计数窄表抄入（无行 = 0）——组装后批量写回
     */
    private int rebuildFeedIndexRows() {
        Map<String, LocalDateTime> approveTimes = new HashMap<>();
        for (ContentReview approveRow : contentReviewDao.selectApproveRowsByTargetType(ReviewTargetTypeVO.PRODUCT.name())) {
            approveTimes.put(approveRow.getTargetId(), approveRow.getCreateTime());
        }
        List<ProductFeedIndexRow> feedIndexRowList = new ArrayList<>();
        for (ProductFeedIndexRow candidate : productDao.selectRebuildRows()) {
            boolean approved = approveTimes.containsKey(candidate.getProductId());
            if (!ProductStatusVO.ON_SALE.name().equals(candidate.getState()) && !approved) {
                // 从未通过审的待审/驳回/草稿商品不凭空进索引
                continue;
            }
            candidate.setFeedTime(approveTimes.getOrDefault(candidate.getProductId(), candidate.getCreateTime()));
            feedIndexRowList.add(candidate);
        }""",
"""    /**
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
        }""")

rep("""    /**
     * 商品入池开单（须处于状态迁移的同事务内）：读回刚翻转为 PENDING_REVIEW 的行的
     * 轮次 / 作者，交 ReviewTaskPoolStore 幂等开单（uk_target_round 吸收重复；
     * entered_at 由开单 SQL NOW() 落库，时刻一律数据库时钟）
     */
    private void enterPoolForPendingReview(String productId) {
        Product pending = productDao.selectById(productId);
        reviewTaskPoolStore.enter(ReviewTargetTypeVO.PRODUCT, productId,
                pending.getReviewRound() == null ? 1 : pending.getReviewRound(),
                pending.getUserId());
    }

""", "")
rep("        product.setReviewRound(productEntity.getReviewRound());\n", "")

open(P, 'w', encoding='utf-8', newline='').write(s)
print("written TradeRepository")
