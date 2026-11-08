import re

P = 'bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/repository/community/CommunityRepository.java'
s = open(P, encoding='utf-8').read()

def cut_method(src, signature):
    # cut from the @Override (or javadoc start directly above) preceding the signature, to the next \n    @Override or class-level marker
    idx = src.index(signature)
    start = src.rindex('    @Override', 0, idx)
    # include a directly-preceding javadoc if present
    m = re.search(r'(/\*\*[\s\S]*?\*/\n)(    @Override\n)?' + re.escape(signature), src)
    if m:
        start = m.start(1)
    nxt = re.search(r'\n    (?:@Override|/\*\*|private|public|static|final|// -)', src[idx:])
    end = idx + nxt.start() + 1
    return src[:start] + src[end:], src[start:end]

def drop(sig):
    global s
    s, removed = cut_method(s, sig)
    print("dropped:", sig.strip()[:70], f"({len(removed)} chars)")

def rep(old, new):
    global s
    assert old in s, old[:80]
    s = s.replace(old, new)

# imports
rep("import com.betterschool.domain.community.model.valobj.CommentReviewMessage;\n", "")
rep("import com.betterschool.domain.community.model.valobj.PostReviewTimeoutVO;\n", "")
rep("import com.betterschool.infrastructure.adapter.repository.moderation.ReviewTaskPoolStore;\n", "")
rep("import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;\n", "")
rep("import com.betterschool.infrastructure.dao.moderation.IContentReviewDao;\n", "")
rep("import com.betterschool.infrastructure.dao.moderation.po.ContentReview;\n", "")

# field
rep("""    /** feed 索引重建取 APPROVE 审批流水（content_review 单表，IContentReviewDao 内该语句零 JOIN） */
    private final IContentReviewDao contentReviewDao;
""", "")

# save(): drop pool enter, add feed index + search sync for NORMAL
rep("""        transactionTemplate.execute(status -> {
            postDao.insert(post);
            // 入池点（spec §5.1）：发布落库即待审，工单开单与状态同事务（草稿行 DRAFT 不入池）；
            // entered_at 由开单 SQL NOW() 落库（时刻一律数据库时钟，≈发布时刻，同事务同秒）
            if (postEntity.getStatus() == PostStatusVO.PENDING_REVIEW) {
                reviewTaskPoolStore.enter(ReviewTargetTypeVO.POST, postEntity.getPostId(),
                        postEntity.getReviewRound(), postEntity.getUserId());
            }
            return null;
        });
        postDetailCache.add(postEntity.getPostId());""",
"""        transactionTemplate.execute(status -> {
            postDao.insert(post);
            if (postEntity.getStatus() == PostStatusVO.NORMAL) {
                // 发布即可见：feed 索引行诞生 + 搜索同步消息，与落库同一事务
                postFeedIndexDao.upsertFeedIndexOnApprove(postEntity.getPostId());
                saveSearchSyncMessage(postEntity.getPostId());
            }
            return null;
        });
        postDetailCache.add(postEntity.getPostId());""")

# submitDraft -> submitDraftWithSearchSync
rep("""    public boolean submitDraft(String postId) {
        Boolean submitted = transactionTemplate.execute(status -> {
            boolean updated = postDao.submitDraft(postId) > 0;
            if (updated) {
                // 入池点：草稿提交进待审，轮次递增同事务可见；工单开单与状态迁移同事务
                // （UPDATE 内联轮次 +1，读回本事务内的新值作开单入参；entered_at 由开单 SQL NOW() 落库）
                Post submittedPost = postDao.selectById(postId);
                reviewTaskPoolStore.enter(ReviewTargetTypeVO.POST, postId, submittedPost.getReviewRound(),
                        submittedPost.getUserId());
            }
            return updated;
        });
        // 草稿从未进过缓存（仅 NORMAL 进缓存），提交后进待审也不可见，无需失效；条件 UPDATE 0 行时上层终态判定
        return Boolean.TRUE.equals(submitted);
    }""",
"""    public boolean submitDraftWithSearchSync(String postId) {
        Boolean submitted = transactionTemplate.execute(status -> {
            boolean updated = postDao.submitDraft(postId) > 0;
            if (updated) {
                // 提交即发布可见：feed 索引行诞生 + 搜索同步消息，与状态迁移同一事务
                postFeedIndexDao.upsertFeedIndexOnApprove(postId);
                saveSearchSyncMessage(postId);
            }
            return updated;
        });
        // 草稿从未进过缓存（仅 NORMAL 进缓存）；提交转 NORMAL 后走缓存装配，条件 UPDATE 0 行时上层终态判定
        postDetailCache.evict(postId);
        return Boolean.TRUE.equals(submitted);
    }""")

# drop methods
drop('public boolean withdraw(String postId)')
drop('public boolean rejectToDraft(String postId)')
drop('public boolean approvePostById(String postId)')
drop('public boolean rejectPostById(String postId)')
drop('public List<PostReviewTimeoutVO> listReviewTimeoutPosts(LocalDateTime before, int limit)')
drop('public boolean approveWithSearchSync(String postId)')
drop('public boolean rejectWithSearchSync(String postId)')
drop('public void forceDeleteWithSearchSync(String postId)')

# deleteWithSearchSync: drop pool close
rep("""            // 软删后贴文不可见，落搜索同步消息（消费方删除 ES 文档）
            saveSearchSyncMessage(postId);
            // 待审帖可被作者删除：退出待审同事务出池（非待审行无在途工单，幂等 0 行无害）
            reviewTaskPoolStore.close(ReviewTargetTypeVO.POST, postId);
            return null;""",
"""            // 软删后贴文不可见，落搜索同步消息（消费方删除 ES 文档）
            saveSearchSyncMessage(postId);
            return null;""")

# rebuild post feed rows: drop approve times
rep("""    /**
     * 重灌行集拼装（原 INSERT..SELECT 一步投影的多查形态）：行集 = NORMAL 帖 ∪ 有过审批通过的帖。
     * 三段取数——① post 全量 (post_id, state, create_time)；② content_review 的 APPROVE 流水
     * （feed_time 真源，经 IContentReviewDao 单表 GROUP BY）；③ like_count 窄表批量抄入（无行 = 0）——
     * 组装后批量写回，与原「feed_time = 最近 APPROVE ?? create_time」口径逐行一致
     */
    private int rebuildFeedIndexRows() {
        Map<String, LocalDateTime> approveTimes = new HashMap<>();
        for (ContentReview approveRow : contentReviewDao.selectApproveRowsByTargetType(ReviewTargetTypeVO.POST.name())) {
            approveTimes.put(approveRow.getTargetId(), approveRow.getCreateTime());
        }
        List<PostFeedIndexRow> postFeedIndexRowList = new ArrayList<>();
        for (PostFeedIndexRow candidate : postDao.selectRebuildRows()) {
            boolean approved = approveTimes.containsKey(candidate.getPostId());
            if (!PostStatusVO.NORMAL.name().equals(candidate.getState()) && !approved) {
                // 从未通过审的待审/驳回/草稿帖不凭空进索引
                continue;
            }
            candidate.setFeedTime(approveTimes.getOrDefault(candidate.getPostId(), candidate.getCreateTime()));
            postFeedIndexRowList.add(candidate);
        }""",
"""    /**
     * 重灌行集拼装（原 INSERT..SELECT 一步投影的多查形态）：行集 = NORMAL 帖。
     * 两段取数——① post 全量 (post_id, state, create_time)，feed_time = create_time；
     * ② like_count 窄表批量抄入（无行 = 0）——组装后批量写回
     */
    private int rebuildFeedIndexRows() {
        List<PostFeedIndexRow> postFeedIndexRowList = new ArrayList<>();
        for (PostFeedIndexRow candidate : postDao.selectRebuildRows()) {
            if (!PostStatusVO.NORMAL.name().equals(candidate.getState())) {
                // 草稿/已删帖不凭空进索引
                continue;
            }
            candidate.setFeedTime(candidate.getCreateTime());
            postFeedIndexRowList.add(candidate);
        }""")

# rebuild comment feed rows: drop approve times
rep("""根评论。三段取数——① 根评论行（comment_id + post_id + create_time）；② like_count 从计数窄表抄入
     * （无行 = 0，与运行期「窄表无行即计数 0」同口径）；③ create_time 取审核记录最近一次 APPROVE 时刻
     * （行诞生时的口径 = 通过时刻，经 IContentReviewDao 单表 GROUP BY，查不到审批流水取评论 create_time 兜底）——
     * 组装后批量写回。只兜索引、不兜窄表：窄表是真值源，重建不改写它
     */
    private int rebuildCommentFeedIndexRows() {
        List<CommentFeedIndexRow> commentFeedIndexRowList = commentDao.selectRebuildRows();
        if (commentFeedIndexRowList.isEmpty()) {
            return 0;
        }
        Map<String, LocalDateTime> approveTimes = new HashMap<>();
        for (ContentReview approveRow : contentReviewDao.selectApproveRowsByTargetType(ReviewTargetTypeVO.COMMENT.name())) {
            approveTimes.put(approveRow.getTargetId(), approveRow.getCreateTime());
        }
        Map<String, PostCommentLikeCount> likeRows""",
"""根评论。两段取数——① 根评论行（comment_id + post_id + create_time）；② like_count 从计数窄表抄入
     * （无行 = 0，与运行期「窄表无行即计数 0」同口径）；feed_time = 评论 create_time——
     * 组装后批量写回。只兜索引、不兜窄表：窄表是真值源，重建不改写它
     */
    private int rebuildCommentFeedIndexRows() {
        List<CommentFeedIndexRow> commentFeedIndexRowList = commentDao.selectRebuildRows();
        if (commentFeedIndexRowList.isEmpty()) {
            return 0;
        }
        Map<String, PostCommentLikeCount> likeRows""")
rep("row.setFeedTime(approveTimes.getOrDefault(row.getCommentId(), row.getCreateTime()));",
    "row.setFeedTime(row.getCreateTime());")

# comment publish path
rep("""    /** 评论"发布即提醒"：审核提醒消息与发布事务同写 outbox（须处于发布事务内调用） */
    @Override
    public void saveReviewMessage(CommentReviewMessage message) {
        outboxMessageStore.save(Constants.COMMENT_REVIEW_TOPIC, message);
    }

    @Override
    public void saveCommentWithReviewMessage(PostCommentEntity commentEntity) {
        transactionTemplate.execute(status -> {
            saveComment(commentEntity);
            saveReviewMessage(new CommentReviewMessage(commentEntity.getCommentId()));
            // 入池点（spec §5.1）：评论发布落库即待审；entered_at 由开单 SQL NOW() 落库，轮次恒 1
            if (commentEntity.getStatus() == PostStatusVO.PENDING_REVIEW) {
                reviewTaskPoolStore.enter(ReviewTargetTypeVO.COMMENT, commentEntity.getCommentId(),
                        1, commentEntity.getUserId());
            }
            return null;
        });
    }""",
"""    @Override
    public void saveVisibleCommentWithCount(PostCommentEntity commentEntity) {
        transactionTemplate.execute(status -> {
            saveComment(commentEntity);
            updatePostCommentCount(commentEntity.getPostId(), 1);
            // 计数窄表行诞生（票 02）：根评论与子回复都入表，赞数恒 0。
            // INSERT IGNORE 只吞重复键（漂移），0 行落 warn 留痕、不覆盖既有计数
            if (postCommentLikeCountDao.insertLikeCountOnApprove(commentEntity.getCommentId()) == 0) {
                log.warn("post_comment_like_count 行已存在，评论发布未新建计数行（commentId={}），需手工对账", commentEntity.getCommentId());
            }
            if (!PostCommentEntity.NO_REFERENCE.equals(commentEntity.getRootId())) {
                // 楼内子回复 → 楼根 reply_count +1（与写入同事务）；子回复不入热评索引
                updateReplyCount(commentEntity.getRootId(), 1);
            } else {
                // 根评论 → 热评索引行诞生（票 01，赞数恒 0）
                postCommentFeedIndexDao.insertFeedIndexOnApprove(commentEntity.getCommentId(), commentEntity.getPostId());
            }
            return null;
        });
    }""")

# approve/reject comment by id removed
drop('public boolean approveCommentById(String commentId)')
drop('public boolean rejectCommentById(String commentId)')

# approveCommentWithCount removed
drop('public void approveCommentWithCount(String commentId, String postId, String rootId)')

# softDeleteCommentWithCount simplification
rep("""    public void softDeleteCommentWithCount(String commentId, String postId, String rootId) {
        transactionTemplate.execute(status -> {
            if (markDeletedFromStatus(commentId, PostStatusVO.NORMAL)) {
                if (!PostCommentEntity.NO_REFERENCE.equals(rootId)) {
                    updatePostCommentCount(postId, -1);
                    updateReplyCount(rootId, -1);
                } else {
                    long visibleReplies = countRepliesByRootId(commentId);
                    int totalDelta = -(1 + (int) visibleReplies);
                    updatePostCommentCount(postId, totalDelta);
                    // 根评论软删 → 热评索引行物理退场（票 01）：行集 = 当前可见根评论，楼消失则排序里也不该有它。
                    // 评论软删是终态、无恢复路径，所以不需要帖子的「行常驻 + state 镜像」
                    postCommentFeedIndexDao.deleteFeedIndex(commentId);
                }
            } else {
                markDeletedFromStatus(commentId, PostStatusVO.PENDING_REVIEW);
            }
            // 待审评论可被作者/帖主删除：退出待审同事务出池（其余状态无在途工单，幂等 0 行无害）
            reviewTaskPoolStore.close(ReviewTargetTypeVO.COMMENT, commentId);
            return null;
        });
    }""",
"""    public void softDeleteCommentWithCount(String commentId, String postId, String rootId) {
        transactionTemplate.execute(status -> {
            if (markDeletedFromStatus(commentId, PostStatusVO.NORMAL)) {
                if (!PostCommentEntity.NO_REFERENCE.equals(rootId)) {
                    updatePostCommentCount(postId, -1);
                    updateReplyCount(rootId, -1);
                } else {
                    long visibleReplies = countRepliesByRootId(commentId);
                    int totalDelta = -(1 + (int) visibleReplies);
                    updatePostCommentCount(postId, totalDelta);
                    // 根评论软删 → 热评索引行物理退场（票 01）：行集 = 当前可见根评论，楼消失则排序里也不该有它。
                    // 评论软删是终态、无恢复路径，所以不需要帖子的「行常驻 + state 镜像」
                    postCommentFeedIndexDao.deleteFeedIndex(commentId);
                }
            }
            // 未命中 = 已终态（DELETED），幂等跳过
            return null;
        });
    }""")

# fillAuthorsAndRejectReasons -> fillAuthors
rep("""    /**
     * 作者昵称（bs_user）与审核未通过理由（content_review 最新 REJECT 行）批量回填：
     * 原 rootCommentJoins / 楼内 / 单行语句里的两个展示 JOIN 列，统一到这里多查一次
     */
    private void fillAuthorsAndRejectReasons(List<PostComment> commentList) {
        Map<String, User> users = usersByIds(commentList.stream().map(PostComment::getUserId).toList());
        Map<String, String> rejectReasons = latestRejectReasons(ReviewTargetTypeVO.COMMENT,
                commentList.stream().map(PostComment::getCommentId).toList());
        for (PostComment row : commentList) {
            User user = users.get(row.getUserId());
            row.setAuthorNickname(user == null ? null : user.getNickname());
            row.setRejectReason(rejectReasons.get(row.getCommentId()));
        }
    }

    /** 每 target 最新一轮 REJECT 行的理由（物理 id 最大者，单表取全量后 Java 裁决；缺行 = 未被拒过） */
    private Map<String, String> latestRejectReasons(ReviewTargetTypeVO targetType, List<String> targetIdList) {
        if (targetIdList.isEmpty()) {
            return Map.of();
        }
        Map<String, ContentReview> latestByTarget = new HashMap<>();
        for (ContentReview row : contentReviewDao.selectRejectRowsByTargetIds(targetType.name(), targetIdList)) {
            latestByTarget.merge(row.getTargetId(), row,
                    (older, newer) -> newer.getId() != null && (older.getId() == null || newer.getId() > older.getId()) ? newer : older);
        }
        Map<String, String> reasons = new HashMap<>(latestByTarget.size());
        latestByTarget.forEach((targetId, row) -> reasons.put(targetId, row.getReason()));
        return reasons;
    }
""",
"""    /**
     * 作者昵称（bs_user）批量回填：原 rootCommentJoins / 楼内 / 单行语句里的展示 JOIN 列，统一到这里多查一次
     */
    private void fillAuthors(List<PostComment> commentList) {
        Map<String, User> users = usersByIds(commentList.stream().map(PostComment::getUserId).toList());
        for (PostComment row : commentList) {
            User user = users.get(row.getUserId());
            row.setAuthorNickname(user == null ? null : user.getNickname());
        }
    }
""")
rep("fillAuthorsAndRejectReasons(commentList);", "fillAuthors(commentList);")
rep("fillAuthorsAndRejectReasons}","fillAuthors}")
rep("commentEntity.setRejectReason(comment.getRejectReason());\n", "")

open(P, 'w', encoding='utf-8', newline='').write(s)
print("written")
