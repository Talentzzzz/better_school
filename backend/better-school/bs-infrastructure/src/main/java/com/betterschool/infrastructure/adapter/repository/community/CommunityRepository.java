package com.betterschool.infrastructure.adapter.repository.community;

import com.betterschool.domain.community.adapter.repository.ICommunityRepository;
import com.betterschool.domain.community.model.entity.PostCommentEntity;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.FeedCursorVO;
import com.betterschool.domain.community.model.valobj.FeedIndexItemVO;
import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.search.model.valobj.SearchEntityType;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.infrastructure.cache.post.PostDetailCache;
import com.betterschool.infrastructure.adapter.repository.CountAction;
import com.betterschool.infrastructure.adapter.repository.CountEvent;
import com.betterschool.infrastructure.adapter.repository.LikeState;
import com.betterschool.infrastructure.merge.MergeConfig;
import com.betterschool.infrastructure.merge.MergeEngine;
import com.betterschool.infrastructure.merge.MergeResult;
import com.betterschool.infrastructure.merge.MergeStrategy;
import com.betterschool.infrastructure.dao.comment.IPostCommentDao;
import com.betterschool.infrastructure.dao.comment.IPostCommentCountDao;
import com.betterschool.infrastructure.dao.comment.IPostCommentFeedIndexDao;
import com.betterschool.infrastructure.dao.comment.IPostCommentLikeCountDao;
import com.betterschool.infrastructure.dao.comment.IPostCommentLikeDao;
import com.betterschool.infrastructure.dao.comment.po.CommentFeedIndexRow;
import com.betterschool.infrastructure.dao.comment.po.PostCommentLikeCount;
import com.betterschool.infrastructure.dao.comment.po.PostComment;
import com.betterschool.infrastructure.dao.post.IPostDao;
import com.betterschool.infrastructure.dao.post.IPostFeedIndexDao;
import com.betterschool.infrastructure.dao.post.IPostLikeCountDao;
import com.betterschool.infrastructure.dao.post.IPostLikeDao;
import com.betterschool.infrastructure.dao.post.IPostViewCountDao;
import com.betterschool.infrastructure.dao.post.IUserViewHistoryDao;
import com.betterschool.infrastructure.dao.post.po.PostFeedIndexRow;
import com.betterschool.infrastructure.dao.post.po.Post;
import com.betterschool.infrastructure.dao.post.po.PostRelationRow;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.User;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 社区仓储（帖子 + 评论合一）：持有一张表一个 DAO 的社区域 DAO 全家桶——
 * IPostDao（post）/ IPostCommentDao（post_comment）+ IPostLikeDao（post_like）/
 * IPostLikeCountDao（post_like_count）/ IPostViewCountDao（post_view_count）/
 * IUserViewHistoryDao（user_view_history）/ IPostFeedIndexDao（post_feed_index）/
 * IPostCommentCountDao（post_comment_count）/ IPostCommentLikeDao（post_comment_like）/
 * IPostCommentLikeCountDao（post_comment_like_count）/ IPostCommentFeedIndexDao（post_comment_feed_index），
 * 多写原子用例在实现内用编程式事务（默认 REQUIRED）。
 * PO / Mapper XML 位置不变；帖子详情缓存仍只依赖 IPostDao（不经本类）。
 *
 * <p>本类同时是计数写入的<b>落库方法</b>（{@link #flushGroup}）：点赞 / 取消赞 / 评论赞 /
 * 帖子浏览的「一组一个事务」在这里，和域内的其他读改写共用同一套 DAO 与事务模板——事务边界不出仓储
 * （{@code backend/CLAUDE.md} §1/§3）。通用合并引擎由本类自己 new（出口就是本类的
 * {@code flushGroup}），四个动作共用一个实例，保证同一动作 × 同一行任一时刻至多一条 flush。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CommunityRepository implements ICommunityRepository {

    private final IPostDao postDao;
    private final IPostCommentDao commentDao;
    /** 帖子点赞关系行（post_like 单表） */
    private final IPostLikeDao postLikeDao;
    /** 帖子点赞计数窄表（post_like_count 单表） */
    private final IPostLikeCountDao postLikeCountDao;
    /** 帖子浏览 UV 加速列（post_view_count 单表） */
    private final IPostViewCountDao postViewCountDao;
    /** 帖子浏览历史明细（user_view_history 单表） */
    private final IUserViewHistoryDao userViewHistoryDao;
    /** 帖子 feed 异构索引（post_feed_index 单表） */
    private final IPostFeedIndexDao postFeedIndexDao;
    /** 评论计数窄表（post_comment_count 单表） */
    private final IPostCommentCountDao postCommentCountDao;
    /** 评论点赞关系行（post_comment_like 单表） */
    private final IPostCommentLikeDao postCommentLikeDao;
    /** 评论点赞计数窄表（post_comment_like_count 单表） */
    private final IPostCommentLikeCountDao postCommentLikeCountDao;
    /** 根评论热评索引（post_comment_feed_index 单表） */
    private final IPostCommentFeedIndexDao postCommentFeedIndexDao;
    /** 作者昵称头像批量回查（SQL 层零 JOIN：列表/详情的 bs_user 信息在这里多查一次拼装） */
    private final IUserDao userDao;
    /** 业务 ID 统一发号入口（uuid-biz-id 票 01）：帖 / 评在 insert 前发号一次写入 */
    private final ObjectMapper objectMapper;
    private final OutboxMessageStore outboxMessageStore;
    private final PostDetailCache postDetailCache;
    private final TransactionTemplate transactionTemplate;
    /**
     * 计数合并引擎（通用 {@link MergeEngine}）：本仓储的四个动作（点赞 / 取消赞 / 评论赞 / 帖子浏览）
     * 共用一个实例——同一张表共用一个引擎，才能保证「同一动作 × 同一行任一时刻至多一条 flush」。
     * 非 final：出口是 {@code this::flushGroup}，只能在依赖注入完成后再建。
     */
    private MergeEngine<CountEvent, LikeState> countEngine;

    /**
     * 引擎由本类自建（通用引擎不是 Spring 托管对象，谁用谁 new），策略里只有两件业务知识：
     * 分组键 = 「动作 × 行主键」（点赞与浏览打不同的表、同一动作不同行也不能混组），
     * 落库 = 本类的 {@link #flushGroup}。
     *
     * <p>用 {@code @Autowired} 方法而不是构造参数：{@code @RequiredArgsConstructor} 生成的构造器里
     * 依赖还没注入完，而 flush 出口必须是本实例；引擎构造期不调用它，注入完再建是安全的。
     */
    @Autowired
    void initCountEngine(MergeConfig mergeConfig) {
        this.countEngine = new MergeEngine<>(mergeConfig, new MergeStrategy<>(
                "community-count",
                // '\u0000' 不会出现在业务 ID（UUID）里，拼出来既严格区分动作又不会与行主键歧义
                event -> event.action().name() + '\u0000' + event.targetId(),
                group -> flushGroup(group.get(0).action(), group.get(0).targetId(), group)));
    }

    /** 停机排空：引擎不是 Spring 托管对象，销毁由持有者发起（顺序在数据源销毁之前） */
    @PreDestroy
    void closeCountEngine() {
        if (countEngine != null) {
            countEngine.close();
        }
    }

    /** 排空等待（排障与集成测试读数用，业务路径不调）：true = 当前无在途件 */
    public boolean awaitIdle(long timeoutMs) {
        return countEngine == null || countEngine.awaitIdle(timeoutMs);
    }

    // ---------- 帖子 ----------

    @Override
    public void save(PostEntity postEntity) {
        // 业务 ID 应用层发号、insert 前一次写入（物理自增 id 只作内部键，不再同值回写）
        postEntity.setPostId(UUID.randomUUID().toString());
        Post post = toPO(postEntity);
        transactionTemplate.execute(status -> {
            postDao.insert(post);
            if (postEntity.getStatus() == PostStatusVO.NORMAL) {
                // 发布即可见：feed 索引行诞生 + 搜索同步消息，与落库同一事务
                postFeedIndexDao.upsertFeedIndexOnApprove(postEntity.getPostId());
                saveSearchSyncMessage(postEntity.getPostId());
            }
            return null;
        });
        postDetailCache.add(postEntity.getPostId());
    }

    @Override
    public PostEntity findPostById(String postId) {
        // 详情缓存：命中直接返回；布隆拦截"一定不存在"；miss 单飞回填（仅 NORMAL 进缓存）。
        // 读路径拆两段（票 05 / spec §3.3）：内容走缓存，三个计数每次回库拼装——缓存与计数零耦合
        PostEntity cachedEntity = postDetailCache.get(postId);
        if (cachedEntity != null) {
            return withLiveCounts(postId, cachedEntity);
        }
        if (!postDetailCache.mightContain(postId)) {
            return null;
        }
        PostEntity loadedEntity = postDetailCache.getOrLoad(postId, () -> {
            Post row = postDao.selectById(postId);
            if (row == null) {
                return null;
            }
            fillPostExtras(List.of(row));
            return toPostEntity(row);
        });
        return loadedEntity == null ? null : withLiveCounts(postId, loadedEntity);
    }

    /**
     * 详情读拆两段之二（票 05 / acceptance C5）：三个计数从窄表逐次取回拼上实体。
     * 缓存里没有计数（进缓存前置空），因此<b>缓存命中路径</b>的 like/comment/view 也恒等于库值——
     * 点赞 / 评论数同步落库后立刻读详情就是新值。读路径不认识合并器，也不做任何在途增量叠加：
     * 点赞请求返回时事务已提交，库值本身就是新的，不需要模拟「缓冲里还有几件」。
     */
    private PostEntity withLiveCounts(String postId, PostEntity postEntity) {
        Post probe = new Post();
        probe.setPostId(postId);
        fillPostExtras(List.of(probe));
        postEntity.setLikeCount(probe.getLikeCount());
        postEntity.setCommentCount(probe.getCommentCount());
        postEntity.setViewCount(probe.getViewCount());
        return postEntity;
    }

    @Override
    public void deletePostById(String postId) {
        transactionTemplate.execute(status -> {
            postDao.softDelete(postId);
            // feed 索引行同事务不可见化（feed-index-uv 票 03）：软删/强删共用此口，行不存在时 0 行静默
            postFeedIndexDao.updateFeedIndexState(postId, PostStatusVO.DELETED.name());
            return null;
        });
        postDetailCache.evict(postId);
    }

    @Override
    public void updateDraft(PostEntity postEntity) {
        postDao.updateDraft(toPO(postEntity));
    }

    @Override
    public boolean submitDraftWithSearchSync(String postId) {
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
    }

    @Override
    public List<PostEntity> pageList(String sort, int offset, int limit) {
        // hot 档排序键 like_count 在窄表（SQL 不能 JOIN 进来）：NORMAL 全集 + 窄表批量回查 + 内存排序切片。
        // 旧直查路径仅作降级（feed-index-uv 票 04/06，主路径是索引），数据集有界，可接受
        List<Post> postList;
        if ("hot".equals(sort)) {
            List<Post> allNormalPostList = postDao.selectNormalRows();
            Map<String, Post> likeRows = countRowsById(postLikeCountDao.selectLikeCountsByPostIds(
                    allNormalPostList.stream().map(Post::getPostId).toList()));
            postList = allNormalPostList.stream()
                    .sorted(Comparator
                            .comparing((Post p) -> countOf(likeRows, p.getPostId(), Post::getLikeCount)).reversed()
                            .thenComparing(Post::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(Post::getPostId, Comparator.reverseOrder()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        } else {
            postList = postDao.selectPage(offset, limit);
        }
        fillPostExtras(postList);
        return postList.stream().map(this::toPostEntity).toList();
    }

    @Override
    public long count() {
        return postDao.count();
    }

    @Override
    public List<FeedIndexItemVO> scanFeedIndex(String sortType, FeedCursorVO cursor, int limit) {
        return postFeedIndexDao.selectFeedIndex(sortType, cursor, limit);
    }

    @Override
    public void rebuildFeedIndex() {
        transactionTemplate.executeWithoutResult(status -> {
            int recalibrated = postViewCountDao.recalibrateViewCounts();
            int orphans = postViewCountDao.deleteViewCountsMissingHistory();
            postFeedIndexDao.clearFeedIndex();
            int rebuilt = rebuildFeedIndexRows();
            log.info("[FeedIndexRebuild] post_feed_index 清空重灌 {} 行；post_view_count 校准 {} 行、清理孤儿 {} 行",
                    rebuilt, recalibrated, orphans);
        });
    }

    /**
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
        }
        if (!postFeedIndexRowList.isEmpty()) {
            Map<String, Post> likeRows = countRowsById(postLikeCountDao.selectLikeCountsByPostIds(
                    postFeedIndexRowList.stream().map(PostFeedIndexRow::getPostId).toList()));
            for (PostFeedIndexRow row : postFeedIndexRowList) {
                row.setLikeCount(countOf(likeRows, row.getPostId(), Post::getLikeCount));
            }
            for (int from = 0; from < postFeedIndexRowList.size(); from += REBUILD_INSERT_BATCH) {
                postFeedIndexDao.insertFeedIndexRows(postFeedIndexRowList.subList(from, Math.min(from + REBUILD_INSERT_BATCH, postFeedIndexRowList.size())));
            }
        }
        return postFeedIndexRowList.size();
    }

    @Override
    public List<PostEntity> pagePostsByUser(String userId, int offset, int limit) {
        List<Post> postList = postDao.selectPageByUser(userId, offset, limit);
        fillPostExtras(postList);
        return postList.stream().map(this::toPostEntity).toList();
    }

    @Override
    public long countPostsByUser(String userId) {
        return postDao.countByUser(userId);
    }

    @Override
    public List<PostEntity> pageLikedByUser(String userId, int offset, int limit) {
        // 「我赞过的」：点赞后帖子可能被驳回/转入待审，仍向点赞者展示
        return pageOf(relationPosts(postLikeDao.selectRelationLikedRows(userId), CommunityRepository::visibleToLiker), offset, limit);
    }

    @Override
    public long countLikedByUser(String userId) {
        return relationPosts(postLikeDao.selectRelationLikedRows(userId), CommunityRepository::visibleToLiker).size();
    }

    @Override
    public List<PostEntity> pageCommentedByUser(String userId, int offset, int limit) {
        return pageOf(relationPosts(commentDao.selectRelationCommentedRows(userId), CommunityRepository::visibleToLiker), offset, limit);
    }

    @Override
    public long countCommentedByUser(String userId) {
        return relationPosts(commentDao.selectRelationCommentedRows(userId), CommunityRepository::visibleToLiker).size();
    }

    @Override
    public List<PostEntity> pageNormalByUser(String userId, int offset, int limit) {
        List<Post> postList = postDao.selectNormalPageByUser(userId, offset, limit);
        fillPostExtras(postList);
        return postList.stream().map(this::toPostEntity).toList();
    }

    @Override
    public long countNormalByUser(String userId) {
        return postDao.countNormalByUser(userId);
    }

    @Override
    public List<PostEntity> pagePublicLikedByUser(String userId, int offset, int limit) {
        // 作者主页「赞过」tab：源帖仅 NORMAL（待审/驳回不外露）
        return pageOf(relationPosts(postLikeDao.selectRelationLikedRows(userId), CommunityRepository::isNormal), offset, limit);
    }

    @Override
    public long countPublicLikedByUser(String userId) {
        return relationPosts(postLikeDao.selectRelationLikedRows(userId), CommunityRepository::isNormal).size();
    }

    @Override
    public List<PostEntity> pageViewHistoryByUser(String userId, int offset, int limit) {
        return pageOf(relationPosts(userViewHistoryDao.selectRelationViewedRows(userId), post -> visibleInHistory(userId, post)), offset, limit);
    }

    @Override
    public long countViewHistoryByUser(String userId) {
        return relationPosts(userViewHistoryDao.selectRelationViewedRows(userId), post -> visibleInHistory(userId, post)).size();
    }

    @Override
    public List<String> selectViewedPostIds(String userId, List<String> postIdList) {
        if (userId == null || postIdList == null || postIdList.isEmpty()) {
            return List.of();
        }
        return userViewHistoryDao.selectViewedPostIds(userId, postIdList);
    }

    @Override
    public boolean isPostLiked(String postId, String userId) {
        return postLikeDao.existsLike(postId, userId) > 0;
    }

    @Override
    public List<String> selectLikedPostIds(String userId, List<String> postIdList) {
        if (userId == null || postIdList == null || postIdList.isEmpty()) {
            return List.of();
        }
        return postLikeDao.selectLikedPostIds(userId, postIdList);
    }

    @Override
    public List<PostEntity> listByIds(List<String> postIdList) {
        List<Post> postList = postDao.selectByIds(postIdList);
        fillPostExtras(postList);
        return postList.stream().map(this::toPostEntity).toList();
    }

    @Override
    public List<String> listIdsAfter(String lastId, int limit) {
        return postDao.selectIdsAfter(lastId, limit);
    }

    @Override
    public void saveSearchSyncMessage(String postId) {
        outboxMessageStore.save(Constants.SEARCH_SYNC_TOPIC,
                new SearchSyncMessage(SearchEntityType.POST.name().toLowerCase(), String.valueOf(postId)));
    }

    @Override
    public void recordPostView(String postId, String userId) {
        // 浏览计数（UV 口径，统一等待语义）：commit 等终态，一组一个事务——明细表 INSERT IGNORE
        // + viewed_at 刷新 + 加速列 += 新增数。匿名访问落不了明细行（user_view_history 主键的一半是
        // user_id），按「INSERT 成功才算浏览」的口径不计入；明细行即真相源，失败/超时抛可重试，
        // 重放被明细唯一键吸收（spec：浏览类从「允许丢」翻转为 commit 语义）
        if (userId == null) {
            return;
        }
        writeView(CountAction.POST_VIEW, postId, userId);
    }

    @Override
    public void deleteWithSearchSync(String postId) {
        transactionTemplate.execute(status -> {
            deletePostById(postId);
            // 软删后贴文不可见，落搜索同步消息（消费方删除 ES 文档）
            saveSearchSyncMessage(postId);
            return null;
        });
    }

    @Override
    public LikeStateVO likePostWithMergedCount(String postId, String userId) {
        // 合并写：提交给通用引擎 → 一组一事务（INSERT IGNORE 关系行 + 计数 +N）→ 返回时计数已精确。
        // 引擎只回两态，FAILED 在这里翻成可重试（件确定没落）
        return writeLike(CountAction.POST_LIKE, postId, userId);
    }

    @Override
    public LikeStateVO unlikePostWithMergedCount(String postId, String userId) {
        return writeLike(CountAction.POST_UNLIKE, postId, userId);
    }

    /**
     * 点赞类写入：提交给通用引擎并等终态（{@code COMPLETED} / {@code FAILED} 两态）。
     *
     * <p>{@code FAILED} 对用户表现为「系统繁忙」：确定没落（整组回滚 / 件从未入队），重试安全
     * ——重复赞被关系行唯一键吸收成 {@code DUPLICATE}。引擎没有「等待超时」这一态：等待的终点由
     * 底层 SQL 的 socketTimeout 保证（见 {@code MergeConfig} 类注释）。
     */
    private LikeStateVO writeLike(CountAction action, String rowId, String userId) {
        MergeResult<LikeState> outcome = countEngine.commit(new CountEvent(action, rowId, userId));
        if (!outcome.isCompleted()) {
            throw retryable(action, rowId, "确定没落（整组已回滚 / 件未入队），可安全重试");
        }
        return toLikeStateVO(outcome.result(), action);
    }

    /**
     * 浏览类写入：与点赞同一套提交语义，但没有业务结果可分——落了就是落了。
     * {@code APPLIED} 只是引擎要求的占位值，这里不看它。
     */
    private void writeView(CountAction action, String rowId, String userId) {
        MergeResult<LikeState> outcome = countEngine.commit(new CountEvent(action, rowId, userId));
        if (!outcome.isCompleted()) {
            throw retryable(action, rowId, "确定没落（整组已回滚 / 件未入队），可安全重试");
        }
    }

    /** 引擎的结果枚举 → 域的结果枚举；两套枚举分开，域不依赖基础设施的合并器包 */
    private static LikeStateVO toLikeStateVO(LikeState state, CountAction action) {
        if (state == null) {
            throw new IllegalStateException("落库方法回填了 null 业务结果：" + action);
        }
        return switch (state) {
            case LIKED -> LikeStateVO.LIKED;
            case UNLIKED -> LikeStateVO.UNLIKED;
            case DUPLICATE -> LikeStateVO.DUPLICATE;
            case NOT_FOUND -> LikeStateVO.NOT_FOUND;
            // APPLIED 是浏览件的占位值；出现在点赞路径 = 落库方法用错了动作分支
            case APPLIED -> throw new IllegalStateException("浏览占位结果不该出现在点赞路径：" + action);
        };
    }

    /**
     * 可重试：行一定没动（或重试会被唯一键吸收），对外统一「系统繁忙，请稍后重试」。
     */
    private static BusinessException retryable(CountAction action, String rowId, String detail) {
        log.warn("计数写入需重试：action={}, rowId={}, 原因={}", action, rowId, detail);
        return new BusinessException(ResponseCode.SYSTEM_ERROR, ResponseCode.SYSTEM_ERROR.getInfo());
    }

    // ---------- 计数合并器的落库方法：一组一个事务（spec §3.1） ----------

    /**
     * 合并器唯一认识的出口：把一组「同一动作 × 同一行主键」的件落成
     * <b>关系行批量写 + 计数一条 UPDATE</b>。
     *
     * <p><b>为什么计数按「影响行数」而不是「事件数」加</b>：批里可能混着已经赞过的用户
     * （前端双击、HTTP 重试、降级路径的并发写）。{@code INSERT IGNORE} 的影响行数恰好是
     * <b>本组真正插入的行数</b>，按它增量，计数与关系行条数就永远对得上，重复赞天然不重复计——
     * 不需要 msgId 去重，也不需要事后校准（spec §3.4）。
     *
     * <p><b>批内撞键怎么定位到具体请求</b>：影响行数只给总数（比如 200 件只插进 197 行），
     * 「哪 3 件撞了键」必须自己查：本事务内按这一批的 {@code (行主键, user_id)} 回查，
     * 命中的那几件回 {@link LikeState#DUPLICATE}，其余照常成功。回查范围严格锁在批内。
     *
     * <p><b>浏览</b>与点赞共用这条出口（{@link #viewPosts}）：{@code INSERT IGNORE} 明细行裁决重复 UV，
     * 影响行数就是增量——同一套「影响行数 = 计数增量」的裁决逻辑，只是裁决键从点赞关系唯一键换成了
     * {@code (user_id, post_id)} 主键。
     */
    public List<LikeState> flushGroup(CountAction action, String shardKey, List<CountEvent> countEventList) {
        return switch (action) {
            case POST_LIKE -> transactionTemplate.execute(status -> likePosts(shardKey, countEventList));
            case POST_UNLIKE -> transactionTemplate.execute(status -> unlikePosts(shardKey, countEventList));
            case COMMENT_LIKE -> transactionTemplate.execute(status -> likeComments(shardKey, countEventList));
            case POST_VIEW -> transactionTemplate.execute(status -> viewPosts(shardKey, countEventList));
            // 商品/跑腿浏览打的是各自的 UV 表，落在各自仓储持有的合并器实例里，不该由社区仓储处理
            case PRODUCT_VIEW, ERRAND_VIEW -> throw new IllegalArgumentException(
                    "商品/跑腿浏览的落库方法在 TradeRepository / ErrandRepository，不该由社区仓储处理：" + action);
        };
    }

    /**
     * 一组帖子浏览（UV 口径，{@link #likePosts} 的镜像）：一组一个事务三步——
     * <ol>
     *   <li>{@code INSERT IGNORE INTO user_view_history}（影响行数 = 本组<b>真正新增</b>的 UV 数，
     *       重复浏览撞 {@code (user_id, post_id)} 主键被裁决掉）；</li>
     *   <li>{@code UPDATE viewed_at}（本组全部键，含刚插入的——对重复浏览是唯一可见效果）；</li>
     *   <li>{@code post_view_count += 新增数}（加速列恒等于明细行数）。</li>
     * </ol>
     *
     * <p><b>为什么计数按「影响行数」而不是「事件数」加</b>：与点赞同理——前端双击、HTTP 重试、
     * 降级并发写都会产生重复键，{@code INSERT IGNORE} 的影响行数恰好是本组真正插入的行数，
     * 加速列与明细行条数就永远对得上（feed-index-uv map 定稿）。失败/超时由调用方重试，
     * 重放撞明细唯一键被吸收、不会重复计。
     *
     * <p><b>为什么要回一组占位结果</b>：浏览件没有「已赞 / 重复」可分，但必须回一组与入参等长、
     * 非 null 的占位值。引擎把「null / 缺位」当作落库方法漏回填，会补 {@code FAILED} 并计入
     * {@code failed}；那样 {@code flushed} 会漏掉这些件，合并比与「accepted − flushed」两个读数同时失真。
     */
    private List<LikeState> viewPosts(String postId, List<CountEvent> countEventList) {
        List<String> userIdList = distinctUserIds(countEventList);
        int inserted = userViewHistoryDao.insertViewHistory(postId, userIdList);
        userViewHistoryDao.touchViewedAt(postId, userIdList);
        if (inserted > 0) {
            postViewCountDao.upsertViewCount(postId, inserted);
        }
        return Collections.nCopies(countEventList.size(), LikeState.APPLIED);
    }

    /** 一组点赞：关系行批量 INSERT IGNORE → 计数 + 真正插入的行数 */
    private List<LikeState> likePosts(String postId, List<CountEvent> countEventList) {
        List<String> userIdList = distinctUserIds(countEventList);
        Set<String> alreadyLiked = new HashSet<>(postLikeDao.selectLikeUserIds(postId, userIdList));
        int inserted = postLikeDao.insertLikes(postId, userIdList);
        if (inserted > 0) {
            postLikeCountDao.upsertLikeCount(postId, inserted);
            // feed 索引 like_count 快照同事务旁路 +N（feed-index-uv 票 03）：热榜排序键与窄表同步推进
            syncFeedIndexLikeCount(postId, inserted);
        }
        // 已经赞过 → DUPLICATE；没赞过 → LIKED；同批重复 → 第一件已写，后面的必然撞键
        return attribute(countEventList, alreadyLiked, LikeState.DUPLICATE, LikeState.LIKED, LikeState.DUPLICATE);
    }

    /** 一组取消赞：关系行批量 DELETE → 计数 - 真正删掉的行数（GREATEST 兜住不为负） */
    private List<LikeState> unlikePosts(String postId, List<CountEvent> countEventList) {
        List<String> userIdList = distinctUserIds(countEventList);
        Set<String> alreadyLiked = new HashSet<>(postLikeDao.selectLikeUserIds(postId, userIdList));
        int deleted = postLikeDao.deleteLikes(postId, userIdList);
        if (deleted > 0) {
            postLikeCountDao.decreaseLikeCount(postId, deleted);
            // feed 索引 like_count 快照同事务旁路 -N（feed-index-uv 票 03）
            syncFeedIndexLikeCount(postId, -deleted);
        }
        // 原本有赞 → 删掉了 → UNLIKED；本来就没赞 → NOT_FOUND；同批重复 → 第二件起已无行可删
        return attribute(countEventList, alreadyLiked, LikeState.UNLIKED, LikeState.NOT_FOUND, LikeState.NOT_FOUND);
    }

    /**
     * feed 索引 like_count 快照 ±N（{@link #likePosts}/{@link #unlikePosts} 的事务内旁路）：
     * 纯 UPDATE 不 upsert——能被赞的帖子行必存在（NORMAL 才可赞，通过事务已建行）。
     * 命中 0 行 = 索引行缺失的存量异常（如回填遗漏），不补行、落日志交重建 job（feed-index-uv 票 07）
     */
    private void syncFeedIndexLikeCount(String postId, int delta) {
        if (postFeedIndexDao.updateFeedIndexLikeCount(postId, delta) == 0) {
            log.warn("post_feed_index 行缺失，like_count 快照未同步（postId={}, delta={}），交由重建 job 修复", postId, delta);
        }
    }

    /** 一组评论点赞：关系行批量 INSERT IGNORE → 窄表 like_count + 真正插入的行数 + 根评论索引旁带 */
    private List<LikeState> likeComments(String commentId, List<CountEvent> countEventList) {
        List<String> userIdList = distinctUserIds(countEventList);
        Set<String> alreadyLiked = new HashSet<>(postCommentLikeDao.selectCommentLikeUserIds(commentId, userIdList));
        int inserted = postCommentLikeDao.insertCommentLikes(commentId, userIdList);
        if (inserted > 0) {
            // 评论点赞计数落窄表（票 02）；根评论的热评索引 like_count 快照同事务旁路 +N（票 01）
            postCommentLikeCountDao.updateLikeCount(commentId, inserted);
            syncCommentFeedIndexLikeCount(commentId, inserted);
        }
        return attribute(countEventList, alreadyLiked, LikeState.DUPLICATE, LikeState.LIKED, LikeState.DUPLICATE);
    }

    /**
     * 根评论热评索引 like_count 快照 ±N（{@link #likeComments} / {@link #unlikeCommentWithCount}
     * 的事务内旁路，票 01）。
     *
     * <p><b>先判根/子</b>：子回复不入索引，它的 UPDATE 必然 0 行，绝不能当漂移告警——所以这一层
     * 必须自己读一次 {@code root_id}，不能靠 UPDATE 的影响行数反推。三种情况分开处理：
     * <ul>
     *   <li>评论行不存在（{@code rootId == null}）：点赞前置检查读的就是这一行，走到这里说明有别的漂移，
     *       留痕但不猜；</li>
     *   <li>子回复：静默返回，这是正常态；</li>
     *   <li>根评论：纯 UPDATE 不 upsert（能被赞的根评论索引行必存在：NORMAL 才可赞、通过事务已建行），
     *       命中 0 行 = 索引行缺失，不补行、落日志交重建 job（与帖子 {@link #syncFeedIndexLikeCount} 同款文案）。</li>
     * </ul>
     */
    private void syncCommentFeedIndexLikeCount(String commentId, int delta) {
        String rootId = commentDao.selectRootId(commentId);
        if (rootId == null) {
            log.warn("评论行不存在，post_comment_feed_index 快照未同步（commentId={}, delta={}）", commentId, delta);
            return;
        }
        if (!PostCommentEntity.NO_REFERENCE.equals(rootId)) {
            return;
        }
        if (postCommentFeedIndexDao.updateFeedIndexLikeCount(commentId, delta) == 0) {
            log.warn("post_comment_feed_index 行缺失，like_count 快照未同步（commentId={}, delta={}），交由重建 job 修复",
                    commentId, delta);
        }
    }

    /**
     * 逐件回填业务结果，顺序与入参一致（落库方法的约定）。
     *
     * <p>三个来源要分开，<b>点赞与取消赞的方向正好相反</b>，所以不能只传一个「被跳过」值：
     * <ul>
     *   <li>{@code ifExisting}：批前就存在这一行（点赞 → {@code DUPLICATE}；取消赞 → {@code UNLIKED}）；</li>
     *   <li>{@code ifAbsent}：批前不存在（点赞 → {@code LIKED}；取消赞 → {@code NOT_FOUND}）；</li>
     *   <li>{@code ifRepeated}：同一个人在这一批里出现多次——DML 对同一个
     *       {@code (行主键, user_id)} 只可能改一次，第二件起必然什么都没改。</li>
     * </ul>
     *
     * <p>回查是<b>本事务内</b>的快照，因此与另一个事务并发写同一 {@code (行主键, user_id)} 时
     * （只可能发生在「缓冲满降级」与「同分片 flush」同时跑的那一刻），逐件的成功/重复判定可能偏向
     * 「成功」；计数按影响行数走，与关系行条数仍然严格相等。
     */
    private static List<LikeState> attribute(List<CountEvent> countEventList, Set<String> existing,
                                             LikeState ifExisting, LikeState ifAbsent, LikeState ifRepeated) {
        Set<String> seen = new HashSet<>();
        List<LikeState> likeStateList = new ArrayList<>(countEventList.size());
        for (CountEvent event : countEventList) {
            if (!seen.add(event.userId())) {
                likeStateList.add(ifRepeated);
            } else {
                likeStateList.add(existing.contains(event.userId()) ? ifExisting : ifAbsent);
            }
        }
        return likeStateList;
    }

    /** 去重后的 user_id（保持入队顺序）：同一个人在一批里出现多次时，SQL 只需要一个占位 */
    private static List<String> distinctUserIds(List<CountEvent> countEventList) {
        Set<String> userIds = new LinkedHashSet<>(countEventList.size());
        for (CountEvent event : countEventList) {
            userIds.add(event.userId());
        }
        return new ArrayList<>(userIds);
    }

    // ---------- 评论 ----------

    @Override
    public void saveComment(PostCommentEntity commentEntity) {
        // 业务 ID 应用层发号、insert 前一次写入（物理自增 id 只作内部键，不再同值回写）
        commentEntity.setCommentId(UUID.randomUUID().toString());
        commentDao.insert(toCommentPO(commentEntity));
        // 时刻一律数据库时钟：create_time 由 SQL NOW() 落库，回读补齐（评论"乐观插顶"响应消费 createdAt；
        // saveCommentWithReviewMessage 的入池 entered_at 同取该 DB 值）
        commentEntity.setCreatedAt(commentDao.selectById(commentEntity.getCommentId()).getCreateTime());
    }

    @Override
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
    }

    @Override
    public List<PostCommentEntity> pageRootByPostId(String postId, String viewerId, String sort, int offset, int limit) {
        if ("hot".equals(sort)) {
            return pageHotRootsByPostId(postId, offset, limit);
        }
        return toCommentEntities(commentDao.selectRootPageByPostId(postId, viewerId, offset, limit));
    }

    /**
     * 最热档两级取数（票 01）：先在 {@code post_comment_feed_index} 上按 (like_count DESC, comment_id)
     * 扫出本页 comment_id 序，再按 id 集回查评论行（保留昵称 / 驳回理由 JOIN），最后按扫描序重排——
     * 排序键只有索引有，回查语句不排序，两级之间不重排就丢了顺序。
     *
     * <p><b>本档不自见</b>：索引行集 = 当前可见根评论（过审 INSERT、软删 DELETE），作者的待审/驳回行
     * 根本不在索引里，因此不再参与最热排序；默认档（id 序）照旧附带自见行。total 口径不变（仅 NORMAL）。
     *
     * <p>索引行还在而评论行已不可见 = 漂移（软删漏删索引行等），跳过不补位——补位要么多查一页、
     * 要么让页大小漂移，重建 job 才是修复手段（票 03）。
     *
     * <p><b>排序键与展示值不同源</b>（票 02）：顺序来自索引快照，展示的 likeCount 仍是
     * {@code post_comment_like_count} 的真值——两条取数通路分开，索引刷偏只动顺序不动数字。
     */
    private List<PostCommentEntity> pageHotRootsByPostId(String postId, int offset, int limit) {
        List<String> commentIdList = postCommentFeedIndexDao.selectRootFeedCommentIds(postId, offset, limit);
        if (commentIdList.isEmpty()) {
            return List.of();
        }
        Map<String, PostComment> rowsById = new HashMap<>(commentIdList.size());
        for (PostComment row : commentDao.selectNormalRootsByIds(commentIdList)) {
            rowsById.put(row.getCommentId(), row);
        }
        List<PostComment> commentList = new ArrayList<>(commentIdList.size());
        for (String commentId : commentIdList) {
            PostComment row = rowsById.get(commentId);
            if (row != null) {
                commentList.add(row);
            }
        }
        return toCommentEntities(commentList);
    }

    @Override
    public long countVisibleRootsByPostId(String postId) {
        return commentDao.countVisibleRootsByPostId(postId);
    }

    @Override
    public List<PostCommentEntity> pageRepliesByRootId(String rootId, String viewerId, int offset, int limit) {
        // 楼内平铺在 toCommentEntities 之外多一段指向评论拼装（原父行自连 JOIN 列），见 toReplyEntities
        return toReplyEntities(commentDao.selectRepliesByRootId(rootId, viewerId, offset, limit));
    }

    @Override
    public long countRepliesForThread(String rootId, String viewerId) {
        return commentDao.countRepliesForThread(rootId, viewerId);
    }

    @Override
    public long countRepliesByRootId(String rootId) {
        return commentDao.countRepliesByRootId(rootId);
    }

    @Override
    public void updateReplyCount(String rootId, int delta) {
        commentDao.updateReplyCount(rootId, delta);
    }

    @Override
    public boolean removeCommentLike(String commentId, String userId) {
        return postCommentLikeDao.deleteCommentLike(commentId, userId) > 0;
    }

    @Override
    public void updateLikeCount(String commentId, int delta) {
        // 计数真源是窄表（票 02）：0 行 = 窄表行缺失的漂移，落 warn 交手工对账，不 ODKU 补行
        if (postCommentLikeCountDao.updateLikeCount(commentId, delta) == 0) {
            log.warn("post_comment_like_count 行缺失，评论 like_count 未变更（commentId={}, delta={}），需手工对账",
                    commentId, delta);
        }
    }

    @Override
    public List<String> selectLikedCommentIds(String userId, List<String> commentIdList) {
        if (userId == null || commentIdList == null || commentIdList.isEmpty()) {
            return List.of();
        }
        return postCommentLikeDao.selectLikedCommentIds(userId, commentIdList);
    }

    @Override
    public List<PostCommentEntity> pageCommentsByUser(String userId, int offset, int limit) {
        // 源帖 NORMAL 过滤与来源帖标题/封面（原 JOIN post 的 WHERE + 投影列）：两段查 + Java 收口
        List<PostComment> visibleCommentList = commentRowsOnVisiblePosts(commentDao.selectNormalRowsByUser(userId));
        List<PostComment> commentList = visibleCommentList.stream().skip(offset).limit(limit).toList();
        return toCommentEntities(commentList);
    }

    @Override
    public long countCommentsByUser(String userId) {
        return commentRowsOnVisiblePosts(commentDao.selectNormalRowsByUser(userId)).size();
    }

    /** 主页「评论」tab 的源帖过滤与标题/封面回填：用户自己的 NORMAL 评论行有界，total = 过滤后集合大小 */
    private List<PostComment> commentRowsOnVisiblePosts(List<PostComment> commentList) {
        if (commentList.isEmpty()) {
            return List.of();
        }
        Map<String, Post> posts = postDao.selectByIds(commentList.stream().map(PostComment::getPostId).distinct().toList())
                .stream().collect(Collectors.toMap(Post::getPostId, post -> post, (a, b) -> a));
        List<PostComment> visibleCommentList = new ArrayList<>(commentList.size());
        for (PostComment row : commentList) {
            Post post = posts.get(row.getPostId());
            if (post == null || !PostStatusVO.NORMAL.name().equals(post.getState())) {
                continue;
            }
            row.setPostTitle(post.getTitle());
            row.setPostCover(post.getCover());
            visibleCommentList.add(row);
        }
        return visibleCommentList;
    }

    @Override
    public PostCommentEntity findCommentById(String commentId) {
        PostComment comment = commentDao.selectById(commentId);
        if (comment == null) {
            return null;
        }
        // 单行即单元素回查（票 02）：与三条列表同一条通吃，不再为单行留一条「直取主列」的岔路
        return toCommentEntities(List.of(comment)).get(0);
    }

    @Override
    public void updatePostCommentCount(String postId, int delta) {
        // 计数不进详情缓存（票 05）：窄表 +1/-1 落库后下一次读自然取到新值，无需任何缓存失效
        postCommentCountDao.upsertPostCommentCount(postId, delta);
    }

    @Override
    public boolean markDeletedFromStatus(String commentId, PostStatusVO fromStatus) {
        // 统一软删：用户删除与举报下架同轨，状态转 DELETED、行保留
        return commentDao.markDeletedFromStatus(commentId, fromStatus.name()) > 0;
    }

    @Override
    public void softDeleteCommentWithCount(String commentId, String postId, String rootId) {
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
    }

    @Override
    public LikeStateVO likeCommentWithMergedCount(String commentId, String userId) {
        // 与帖子点赞同一条合并通道，只是 shardKey 取评论主键：一评一条 UPDATE（计数落 post_comment_like_count 窄表，票 02）
        return writeLike(CountAction.COMMENT_LIKE, commentId, userId);
    }

    @Override
    public void unlikeCommentWithCount(String commentId, String userId) {
        transactionTemplate.execute(status -> {
            if (!removeCommentLike(commentId, userId)) {
                throw new BusinessException(ResponseCode.PARAM_ERROR, "尚未点赞");
            }
            updateLikeCount(commentId, -1);
            // 根评论索引快照同步 -1（票 01）；子回复在 syncCommentFeedIndexLikeCount 里就地返回
            syncCommentFeedIndexLikeCount(commentId, -1);
            return null;
        });
    }

    /**
     * 热评索引全量重建（票 03）：清空 + 重灌收在一个事务里，读者要么看到重建前、要么看到重建后的
     * 完整索引，不见空窗期。行集 = {@code root_id = '0' AND state = 'NORMAL'} 根评论，赞数从窄表抄入。
     *
     * <p><b>只兜索引、不兜窄表</b>：{@code post_comment_like_count} 是计数真值源，重建不校准它——
     * 重建能修「索引行缺失 / 快照被直改 / 已软删的残行 / 待审凭空进索引」，修不了「窄表自身漂移」。
     * 窄表缺行的根评论重灌后赞数为 0（与「窄表无行即计数 0」的存量语义一致），而且<b>点赞也补不回来</b>：
     * 票 02 的点赞只 UPDATE 窄表、命中 0 行仅落 warn、不 ODKU 造行。要真回正得先手工补窄表行再重跑重建，
     * 别把重建当窄表的对账手段。
     */
    @Override
    public void rebuildCommentFeedIndex() {
        transactionTemplate.executeWithoutResult(status -> {
            postCommentFeedIndexDao.clearFeedIndex();
            int rebuilt = rebuildCommentFeedIndexRows();
            log.info("[CommentFeedIndexRebuild] post_comment_feed_index 清空重灌 {} 行", rebuilt);
        });
    }

    /**
     * 重灌行集拼装（原 INSERT..SELECT 一步投影的多查形态）：行集 = {@code root_id = '0' AND state = 'NORMAL'}
     * 根评论。两段取数——① 根评论行（comment_id + post_id + create_time）；② like_count 从计数窄表抄入
     * （无行 = 0，与运行期「窄表无行即计数 0」同口径）；feed_time = 评论 create_time——
     * 组装后批量写回。只兜索引、不兜窄表：窄表是真值源，重建不改写它
     */
    private int rebuildCommentFeedIndexRows() {
        List<CommentFeedIndexRow> commentFeedIndexRowList = commentDao.selectRebuildRows();
        if (commentFeedIndexRowList.isEmpty()) {
            return 0;
        }
        Map<String, PostCommentLikeCount> likeRows = new HashMap<>(commentFeedIndexRowList.size());
        for (PostCommentLikeCount likeRow : postCommentLikeCountDao.selectLikeCountsByIds(
                commentFeedIndexRowList.stream().map(CommentFeedIndexRow::getCommentId).toList())) {
            likeRows.put(likeRow.getCommentId(), likeRow);
        }
        for (CommentFeedIndexRow row : commentFeedIndexRowList) {
            row.setFeedTime(row.getCreateTime());
            PostCommentLikeCount likeRow = likeRows.get(row.getCommentId());
            row.setLikeCount(likeRow == null ? 0L : likeRow.getLikeCount());
        }
        for (int from = 0; from < commentFeedIndexRowList.size(); from += REBUILD_INSERT_BATCH) {
            postCommentFeedIndexDao.insertFeedIndexRows(commentFeedIndexRowList.subList(from, Math.min(from + REBUILD_INSERT_BATCH, commentFeedIndexRowList.size())));
        }
        return commentFeedIndexRowList.size();
    }

    // ---------- 拼装辅助：计数窄表 / 作者信息 / 关系列表两段查（SQL 层零 JOIN 的 Java 收口点） ----------

    /** 重灌行集批量写回的分片大小（防 foreach 语句过长） */
    private static final int REBUILD_INSERT_BATCH = 500;

    /**
     * 页内帖子行补齐三个计数 + 作者昵称头像：三张窄表各一次 IN 批量回查、bs_user 一次 IN 批量回查。
     * 缺行补 0 / 补 null（与原 LEFT JOIN COALESCE 合成口径一致）；列表、详情、降级热榜共用
     */
    private void fillPostExtras(List<Post> postList) {
        if (postList.isEmpty()) {
            return;
        }
        List<String> postIdList = postList.stream().map(Post::getPostId).toList();
        Map<String, Post> likeRows = countRowsById(postLikeCountDao.selectLikeCountsByPostIds(postIdList));
        Map<String, Post> commentRows = countRowsById(postCommentCountDao.selectCommentCountsByPostIds(postIdList));
        Map<String, Post> viewRows = countRowsById(postViewCountDao.selectViewCountsByPostIds(postIdList));
        Map<String, User> users = usersByIds(postList.stream().map(Post::getUserId).toList());
        for (Post row : postList) {
            row.setLikeCount(countOf(likeRows, row.getPostId(), Post::getLikeCount));
            row.setCommentCount(countOf(commentRows, row.getPostId(), Post::getCommentCount));
            row.setViewCount(countOf(viewRows, row.getPostId(), Post::getViewCount));
            User user = users.get(row.getUserId());
            if (user != null) {
                row.setAuthorNickname(user.getNickname());
                row.setAuthorAvatar(user.getAvatar());
            }
        }
    }

    /** 部分列计数行 → postId 索引 */
    private static Map<String, Post> countRowsById(List<Post> countPostList) {
        return countPostList.stream().collect(Collectors.toMap(Post::getPostId, row -> row, (a, b) -> a));
    }

    /** 窄表缺行 = 计数 0（与原 LEFT JOIN COALESCE(x, 0) 同口径） */
    private static Integer countOf(Map<String, Post> rows, String postId, Function<Post, Integer> getter) {
        Post row = rows.get(postId);
        return row == null ? 0 : getter.apply(row);
    }

    /** userId 集 → bs_user 批量回查索引 */
    private Map<String, User> usersByIds(Collection<String> userIds) {
        List<String> userIdList = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (userIdList.isEmpty()) {
            return Map.of();
        }
        return userDao.selectByIds(userIdList).stream()
                .collect(Collectors.toMap(User::getUserId, user -> user, (a, b) -> a));
    }

    /**
     * 关系列表两段查：关系行（点赞/评论/浏览，单表主查已按排序键有序）→ 帖子行按 id 集批量回查 →
     * 可见性谓词 Java 收口（原 SQL JOIN WHERE 里的 state 过滤），保持关系行顺序。
     * total 语义 = 过滤后集合大小，与原 JOIN COUNT 同口径（每用户关系行有界）
     */
    private List<Post> relationPosts(List<PostRelationRow> postRelationRowList, Predicate<Post> visible) {
        if (postRelationRowList.isEmpty()) {
            return List.of();
        }
        List<String> postIdList = postRelationRowList.stream().map(PostRelationRow::getPostId).distinct().toList();
        Map<String, Post> postsById = postDao.selectByIds(postIdList).stream()
                .collect(Collectors.toMap(Post::getPostId, post -> post, (a, b) -> a));
        List<Post> visiblePostList = new ArrayList<>(postRelationRowList.size());
        for (PostRelationRow row : postRelationRowList) {
            Post post = postsById.get(row.getPostId());
            if (post != null && visible.test(post)) {
                visiblePostList.add(post);
            }
        }
        return visiblePostList;
    }

    /** 可见行切片成页：只对页内行做计数/作者回查，过滤集有界（每用户关系行有界） */
    private List<PostEntity> pageOf(List<Post> postList, int offset, int limit) {
        List<Post> pagePostList = postList.stream().skip(offset).limit(limit).toList();
        fillPostExtras(pagePostList);
        return pagePostList.stream().map(this::toPostEntity).toList();
    }

    /** 点赞者视角可见性：NORMAL 照常展示（草稿从未公开、已删除一律过滤） */
    private static boolean visibleToLiker(Post post) {
        return PostStatusVO.NORMAL.name().equals(post.getState());
    }

    /** 他人视角可见性（作者主页 tab）：仅 NORMAL */
    private static boolean isNormal(Post post) {
        return PostStatusVO.NORMAL.name().equals(post.getState());
    }

    /** 浏览历史可见性：NORMAL 公开；本人看自己浏览过的非删除帖照常展示（详情对作者可见） */
    private static boolean visibleInHistory(String viewerId, Post post) {
        return isNormal(post)
                || (viewerId.equals(post.getUserId()) && !PostStatusVO.DELETED.name().equals(post.getState()));
    }

    // ---------- 行转换 ----------

    private Post toPO(PostEntity postEntity) {
        Post post = new Post();
        post.setPostId(postEntity.getPostId());
        post.setUserId(postEntity.getUserId());
        post.setTitle(postEntity.getTitle());
        post.setContent(postEntity.getContent());
        post.setImages(toImagesJson(postEntity.getImages()));
        post.setCover(postEntity.getCover());
        post.setState(postEntity.getStatus().name());
        // post.view_count 停写（feed-index-uv 票 02）：浏览 UV 的真相源是 user_view_history，加速列 post_view_count
        return post;
    }

    private PostEntity toPostEntity(Post post) {
        if (post == null) {
            return null;
        }
        PostEntity postEntity = new PostEntity();
        postEntity.setPostId(post.getPostId());
        postEntity.setUserId(post.getUserId());
        postEntity.setTitle(post.getTitle());
        postEntity.setContent(post.getContent());
        postEntity.setImages(toImagesList(post.getImages()));
        postEntity.setCover(post.getCover());
        postEntity.setStatus(PostStatusVO.valueOf(post.getState()));
        // 空安全：详情内容查询（selectById）已不带计数列（票 05，计数每次读单独取），投影列为 null
        postEntity.setLikeCount(post.getLikeCount() == null ? 0 : post.getLikeCount());
        postEntity.setCommentCount(post.getCommentCount() == null ? 0 : post.getCommentCount());
        postEntity.setViewCount(post.getViewCount() == null ? 0 : post.getViewCount());
        postEntity.setCreatedAt(post.getCreateTime());
        postEntity.setAuthorNickname(post.getAuthorNickname());
        postEntity.setAuthorAvatar(post.getAuthorAvatar());
        return postEntity;
    }

    private String toImagesJson(List<String> imageList) {
        try {
            return objectMapper.writeValueAsString(imageList);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("帖子图片序列化失败", e);
        }
    }

    private List<String> toImagesList(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(imagesJson, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            log.warn("帖子图片反序列化失败，按空处理：{}", imagesJson, e);
            return List.of();
        }
    }

    /**
     * 评论行 → 实体，并把点赞数从计数窄表投影进 {@code PostComment.likeCount}（票 02，spec §4.2/§4.4）。
     *
     * <p><b>为什么不在 SQL 里 JOIN</b>：与 feed 形态的 {@code listByIds} 同思路——主查询只拿评论行，
     * 页内 id 集一次 {@code IN} 回查窄表；三条列表（根列表两档 / 楼内 / 主页 tab）与单行共用这一条通路，
     * 不为一处写一条 JOIN、另一处写一条子查询。作者昵称同理（bs_user
     * 批量回查，{@link #fillAuthors}）。
     *
     * <p>{@code likeCount} 等填在 PO 上再交给 {@link #toCommentEntity}：PO 是本次查询自己组装的一次性载体，
     * 链上的搬运（PO → Entity → DTO）因此零改动（spec §4.4 的投影填充口径，同 Product）。
     *
     * <p>缺行补 0（存量语义）：窄表行诞生于审核通过，票 02 之前过审的历史评论没有计数行。
     */
    private List<PostCommentEntity> toCommentEntities(List<PostComment> commentList) {
        if (commentList.isEmpty()) {
            return List.of();
        }
        Map<String, Long> likeCounts = likeCountsOf(commentList.stream().map(PostComment::getCommentId).toList());
        fillAuthors(commentList);
        List<PostCommentEntity> commentEntityList = new ArrayList<>(commentList.size());
        for (PostComment commentRow : commentList) {
            commentRow.setLikeCount(likeCounts.getOrDefault(commentRow.getCommentId(), 0L));
            commentEntityList.add(toCommentEntity(commentRow));
        }
        return commentEntityList;
    }

    /**
     * 作者昵称（bs_user）批量回填：原 rootCommentJoins / 楼内 / 单行语句里的展示 JOIN 列，统一到这里多查一次
     */
    private void fillAuthors(List<PostComment> commentList) {
        Map<String, User> users = usersByIds(commentList.stream().map(PostComment::getUserId).toList());
        for (PostComment row : commentList) {
            User user = users.get(row.getUserId());
            row.setAuthorNickname(user == null ? null : user.getNickname());
        }
    }

    /**
     * 楼内平铺的指向评论拼装（原父行自连 LEFT JOIN 列）：parent 行单表批量回查、忽略其状态
     * （被指向评论被删/未通过时昵称仍可读；父行缺失 = 老楼/漂移，reply_to 落 null 与原 LEFT JOIN 一致），
     * 指向昵称再经 bs_user 批量回查。作者/驳回理由等公共列仍走 {@link #toCommentEntities}
     */
    private List<PostCommentEntity> toReplyEntities(List<PostComment> commentList) {
        if (commentList.isEmpty()) {
            return List.of();
        }
        List<String> parentIdList = commentList.stream().map(PostComment::getParentId)
                .filter(parentId -> parentId != null && !PostCommentEntity.NO_REFERENCE.equals(parentId))
                .distinct().toList();
        Map<String, PostComment> parentsById = parentIdList.isEmpty() ? Map.of()
                : commentDao.selectByIds(parentIdList).stream()
                        .collect(Collectors.toMap(PostComment::getCommentId, parent -> parent, (a, b) -> a));
        Map<String, User> users = usersByIds(parentsById.values().stream()
                .map(PostComment::getUserId).filter(Objects::nonNull).toList());
        for (PostComment row : commentList) {
            PostComment parent = parentsById.get(row.getParentId());
            if (parent == null) {
                continue;
            }
            row.setReplyToUserId(parent.getUserId());
            User parentUser = users.get(parent.getUserId());
            row.setReplyToNickname(parentUser == null ? null : parentUser.getNickname());
        }
        return toCommentEntities(commentList);
    }

    /** 页内评论 id 集 → 窄表赞数（缺行不在返回值里，由调用方按 0 处理） */
    private Map<String, Long> likeCountsOf(List<String> commentIdList) {
        Map<String, Long> likeCounts = new HashMap<>(commentIdList.size());
        for (PostCommentLikeCount countRow : postCommentLikeCountDao.selectLikeCountsByIds(commentIdList)) {
            likeCounts.put(countRow.getCommentId(), countRow.getLikeCount());
        }
        return likeCounts;
    }

    private PostComment toCommentPO(PostCommentEntity commentEntity) {
        PostComment comment = new PostComment();
        comment.setCommentId(commentEntity.getCommentId());
        comment.setPostId(commentEntity.getPostId());
        comment.setUserId(commentEntity.getUserId());
        comment.setContent(commentEntity.getContent());
        comment.setState(commentEntity.getStatus().name());
        comment.setRootId(commentEntity.getRootId());
        comment.setParentId(commentEntity.getParentId());
        return comment;
    }

    private PostCommentEntity toCommentEntity(PostComment comment) {
        if (comment == null) {
            return null;
        }
        PostCommentEntity commentEntity = new PostCommentEntity();
        commentEntity.setCommentId(comment.getCommentId());
        commentEntity.setPostId(comment.getPostId());
        commentEntity.setUserId(comment.getUserId());
        commentEntity.setContent(comment.getContent());
        commentEntity.setStatus(PostStatusVO.valueOf(comment.getState()));
        commentEntity.setRootId(comment.getRootId());
        commentEntity.setParentId(comment.getParentId());
        commentEntity.setLikeCount(comment.getLikeCount());
        commentEntity.setReplyCount(comment.getReplyCount());
        commentEntity.setCreatedAt(comment.getCreateTime());
        commentEntity.setAuthorNickname(comment.getAuthorNickname());
        commentEntity.setReplyToUserId(comment.getReplyToUserId());
        commentEntity.setReplyToNickname(comment.getReplyToNickname());
                commentEntity.setPostTitle(comment.getPostTitle());
        commentEntity.setPostCover(comment.getPostCover());
        return commentEntity;
    }
}
