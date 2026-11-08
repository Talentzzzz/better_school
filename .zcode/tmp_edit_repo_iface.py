def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (p, old[:80])
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print("ok", p)

# ---- ICommunityRepository interface ----
edit('bs-domain/src/main/java/com/betterschool/domain/community/adapter/repository/ICommunityRepository.java', [
("""    /** 提交草稿：DRAFT -> PENDING_REVIEW（轮次 +1、锚定进入待审时间），返回 false = 状态已变化 */
    boolean submitDraft(String postId);

    /** 撤回待审：PENDING_REVIEW -> DRAFT，返回 false = 状态已变化 */
    boolean withdraw(String postId);

    /** 被拒转草稿箱：REJECTED -> DRAFT，返回 false = 状态已变化 */
    boolean rejectToDraft(String postId);

    /** 软删：status -> DELETED */
    void deletePostById(String postId);

    /** 帖子审核通过：PENDING_REVIEW → NORMAL（条件 UPDATE，返回是否本次生效） */
    boolean approvePostById(String postId);

    /** 帖子审核驳回：PENDING_REVIEW → REJECTED（条件 UPDATE，返回是否本次生效） */
    boolean rejectPostById(String postId);

    /** 待审超时帖子扫描（部分字段：postId + reviewRound，供 dedup_key 区分重提轮） */
    List<PostReviewTimeoutVO> listReviewTimeoutPosts(LocalDateTime before, int limit);

    List<PostEntity> pageList(String sort, int offset, int limit);""",
"""    /** 提交草稿：DRAFT -> NORMAL（返回 false = 状态已变化） */
    boolean submitDraftWithSearchSync(String postId);

    /** 软删：status -> DELETED */
    void deletePostById(String postId);

    List<PostEntity> pageList(String sort, int offset, int limit);"""),
("""    /** 软删帖子 + 落搜索同步消息（消费方删除 ES 文档），同一事务原子 */
    void deleteWithSearchSync(String postId);

    /** 审核通过（条件 UPDATE）+ 落搜索同步消息，同一事务；返回 false = 0 行（并发双审/重试） */
    boolean approveWithSearchSync(String postId);

    /** 审核驳回（条件 UPDATE）+ 落搜索同步消息，同一事务；返回 false = 0 行（并发双审/重试） */
    boolean rejectWithSearchSync(String postId);

    /** 强制下架（软删 + 搜索同步），同一事务；与 {@link #deleteWithSearchSync} 同体，保留语义独立的入口 */
    void forceDeleteWithSearchSync(String postId);
""",
"""    /** 软删帖子 + 落搜索同步消息（消费方删除 ES 文档），同一事务原子 */
    void deleteWithSearchSync(String postId);
"""),
("""    /** 落一条"评论审核"待发送消息（ADR-0002：必须与评论写入同事务调用，实现层写 outbox 表） */
    void saveReviewMessage(CommentReviewMessage message);

    /** 评论发布（落库 + 取回业务 id）+ 落审核消息，同一事务（零同步审核，ADR-0002） */
    void saveCommentWithReviewMessage(PostCommentEntity commentEntity);
""",
"""    /** 评论发布即可见（落库 + 取回业务 id）+ 评论数/楼根回复数/热评索引同事务推进 */
    void saveVisibleCommentWithCount(PostCommentEntity commentEntity);
"""),
("""    /** 评论审核通过：PENDING_REVIEW → NORMAL（条件 UPDATE，返回是否本次生效） */
    boolean approveCommentById(String commentId);

    /** 评论审核驳回：PENDING_REVIEW → REJECTED（条件 UPDATE，返回是否本次生效） */
    boolean rejectCommentById(String commentId);

    /**
     * 统一软删：仅指定前置状态可翻转为 DELETED（用户删除与举报下架同轨）。
     * 白名单条件 UPDATE：扣减计数与否由实际翻转的前置状态决定，防审核并发下读旧态漂移；返回是否本次生效
     */
    boolean markDeletedFromStatus(String commentId, PostStatusVO fromStatus);

    // ---------- 评论：多写原子用例（实现内编程式事务，REQUIRED） ----------

    /**
     * 审核通过（条件 UPDATE，0 行即抛 BUSINESS_ERROR 并回滚）+ 帖子评论数 +1 + 楼根 reply_count +1，
     * 同一事务（影响行数 = 1 才是本次刚通过，幂等）；<b>根评论与子回复都在计数窄表
     * {@code post_comment_like_count} 里诞生一行</b>（赞数 0，INSERT IGNORE，行已存在落 warn），
     * <b>根评论另在热评索引里诞生一行</b>（子回复不入索引），全部与状态翻转同事务
     */
    void approveCommentWithCount(String commentId, String postId, String rootId);

    /**
     * 统一软删（用户删除与举报下架同轨，物理 DELETE 废弃）：状态转 DELETED、行保留，计数增减与状态变更同一事务。
     * 扣减与否由条件 UPDATE 实际命中的前置状态决定（读到的旧态仅用于定位，防审核并发漂移）：
     * NORMAL 命中才扣——子回复：总数 -1 + 楼根 reply_count -1；根评论：整楼消失，总数扣 1 + 楼内可见子回复数，
     * 且同事务删掉它的热评索引行（行集 = 当前可见根评论；评论软删是终态，不学帖子留 state 镜像）；
     * PENDING_REVIEW 命中：从未计数不动；均未命中 = 已终态（DELETED/REJECTED），幂等跳过
     */
    void softDeleteCommentWithCount(String commentId, String postId, String rootId);""",
"""    /**
     * 统一软删：仅 NORMAL 可翻转为 DELETED（用户删除与举报下架同轨）。
     * 白名单条件 UPDATE：扣减计数与否由实际翻转的前置状态决定，防并发下读旧态漂移；返回是否本次生效
     */
    boolean markDeletedFromStatus(String commentId, PostStatusVO fromStatus);

    // ---------- 评论：多写原子用例（实现内编程式事务，REQUIRED） ----------

    /**
     * 统一软删（用户删除与举报下架同轨，物理 DELETE 废弃）：状态转 DELETED、行保留，计数增减与状态变更同一事务。
     * 扣减与否由条件 UPDATE 实际命中的前置状态决定（读到的旧态仅用于定位，防并发漂移）：
     * NORMAL 命中才扣——子回复：总数 -1 + 楼根 reply_count -1；根评论：整楼消失，总数扣 1 + 楼内可见子回复数，
     * 且同事务删掉它的热评索引行（行集 = 当前可见根评论；评论软删是终态，不学帖子留 state 镜像）；
     * 未命中 = 已终态（DELETED），幂等跳过
     */
    void softDeleteCommentWithCount(String commentId, String postId, String rootId);"""),
])
