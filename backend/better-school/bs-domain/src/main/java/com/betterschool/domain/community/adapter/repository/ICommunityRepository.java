package com.betterschool.domain.community.adapter.repository;

import com.betterschool.domain.community.model.entity.PostCommentEntity;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.FeedCursorVO;
import com.betterschool.domain.community.model.valobj.FeedIndexItemVO;
import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.domain.community.model.valobj.PostStatusVO;

import java.util.List;

/**
 * 社区仓储（帖子 + 评论合一，一域一仓储）：覆盖 community 领域服务所需全部持久化能力，
 * 由 infrastructure 实现（实现内持社区域一张表一个 DAO 的全家桶：IPostDao + IPostCommentDao
 * 及 post_like / post_like_count / post_view_count / user_view_history / post_feed_index /
 * post_comment_count / post_comment_like / post_comment_like_count / post_comment_feed_index 各自的窄表 DAO）。
 * 需要多 DAO 原子写的能力收成仓储方法，事务在实现内（编程式，默认 REQUIRED）
 */
public interface ICommunityRepository {

    // ---------- 帖子 ----------

    void save(PostEntity postEntity);

    /** 单个帖子（帖子/评论同表域，故两读入口显式区分命名） */
    PostEntity findPostById(String postId);

    /** 覆盖草稿内容（仅 DRAFT 行生效） */
    void updateDraft(PostEntity postEntity);

    /** 提交草稿：DRAFT -> NORMAL（返回 false = 状态已变化） */
    boolean submitDraftWithSearchSync(String postId);

    /** 软删：status -> DELETED */
    void deletePostById(String postId);

    List<PostEntity> pageList(String sort, int offset, int limit);

    long count();

    /**
     * 主页 feed 索引序扫描（feed-index-uv 票 04）：post_feed_index 只取 NORMAL，按 (排序键, post_id) DESC
     * 索引序出 id（time = feed_time、hot = like_count），cursor 非 null 时按
     * {@code WHERE (排序键, post_id) < (?, ?)} 续页。排序键随行带出（游标锚点用）；
     * 查询异常向上抛，由调用方降级直查
     */
    List<FeedIndexItemVO> scanFeedIndex(String sortType, FeedCursorVO cursor, int limit);

    /**
     * feed 索引全量重建（feed-index-uv 票 07，兜底恢复手段）：清空重灌 post_feed_index（post 表 +
     * post_like_count 窄表 + 审核记录投影），顺带把 post_view_count 校准成 user_view_history 行数。
     * 先删后写、幂等可重跑；四条语句一个事务（读者要么看到重建前要么重建后的完整索引，不见空窗）
     */
    void rebuildFeedIndex();

    /** 我的帖子分页（作者视角）：NORMAL/DRAFT，排除已删除 */
    List<PostEntity> pagePostsByUser(String userId, int offset, int limit);

    long countPostsByUser(String userId);

    /** 我点赞过的帖子分页（按点赞时间倒序） */
    List<PostEntity> pageLikedByUser(String userId, int offset, int limit);

    long countLikedByUser(String userId);

    /** 我评论过的帖子分页（去重，按最近评论时间倒序） */
    List<PostEntity> pageCommentedByUser(String userId, int offset, int limit);

    long countCommentedByUser(String userId);

    /** 某用户的公开帖子分页（作者主页「笔记」tab）：仅 NORMAL */
    List<PostEntity> pageNormalByUser(String userId, int offset, int limit);

    long countNormalByUser(String userId);

    /** 某用户赞过的帖子分页（作者主页「赞过」tab）：源帖仅 NORMAL，按点赞时间倒序 */
    List<PostEntity> pagePublicLikedByUser(String userId, int offset, int limit);

    long countPublicLikedByUser(String userId);

    /**
     * 我的浏览历史分页（feed-index-uv 票 05）：user_view_history 明细按最近浏览时刻倒序，
     * 只含仍可见的帖子——NORMAL 公开；本人浏览过的草稿帖照常展示（详情对作者可见）；
     * 已删除一律过滤（点卡片要能进详情，非可见态进详情是 404）
     */
    List<PostEntity> pageViewHistoryByUser(String userId, int offset, int limit);

    /** 浏览历史总数（与 {@link #pageViewHistoryByUser} 同一可见性口径） */
    long countViewHistoryByUser(String userId);

    /** 当前用户浏览过的帖子 id 集合（feed 已读灰显批量回显；未登录传 null 返回空集合） */
    List<String> selectViewedPostIds(String userId, List<String> postIdList);

    boolean isPostLiked(String postId, String userId);

    /** 当前用户已点赞的帖子 id 集合（列表 liked 状态批量回显；未登录传 null 返回空集合） */
    List<String> selectLikedPostIds(String userId, List<String> postIdList);

    /** 按 id 列表查询（不分状态，搜索同步/搜索命中反查用；返回顺序与入参无关，调用方按 id 重排） */
    List<PostEntity> listByIds(List<String> postIdList);

    /** 搜索索引全量重建的游标页：id > lastId 按 id 升序取 limit 条（无界结果集一律游标化） */
    List<String> listIdsAfter(String lastId, int limit);

    /** 落一条"搜索索引同步"待发送消息（必须与帖子状态变更同事务调用，实现层写 outbox 表） */
    void saveSearchSyncMessage(String postId);

    // ---------- 帖子：多写原子用例（实现内编程式事务，REQUIRED） ----------

    /**
     * 帖子浏览（UV 口径）：fire-and-forget，入队即返回，窗口过后一组一个事务——
     * {@code INSERT IGNORE INTO user_view_history}（affected = 新增 UV 数）→ 刷新本组 {@code viewed_at}
     * → {@code post_view_count += 新增数}。
     *
     * <p>真相源是明细表（一人一帖一行），<b>INSERT 成功才算一次浏览</b>：重复进详情只刷 {@code viewed_at}
     * 不加数；{@code userId == null}（匿名）落不了明细行，不计入。仍然<b>允许丢</b>：失败只计数、不告警、
     * 不回补，也<b>不影响请求结果</b>（spec §1.2 / §3.2）。HTTP 时延与合并窗口无关。
     *
     * @param userId 浏览者；null = 匿名访问，整次浏览不记录
     */
    void recordPostView(String postId, String userId);

    /** 软删帖子 + 落搜索同步消息（消费方删除 ES 文档），同一事务原子 */
    void deleteWithSearchSync(String postId);

    /**
     * 点赞（合并写）：窗口合并成一组一事务落库，<b>返回时计数已精确</b>（与关系行条数相等，无需等待）。
     *
     * @return {@code LIKED} 已写入；{@code DUPLICATE} 已经赞过（唯一键裁决，计数未重复推进）
     * @throws com.betterschool.types.exception.BusinessException 等超时 / 落库失败：可安全重试（重复由唯一键吸收）
     */
    LikeStateVO likePostWithMergedCount(String postId, String userId);

    /** 取消点赞（合并写，计数按本组真正删掉的行数回落，不为负）；{@code NOT_FOUND} = 本来就没赞 */
    LikeStateVO unlikePostWithMergedCount(String postId, String userId);

    // ---------- 评论 ----------

    void saveComment(PostCommentEntity commentEntity);

    /** 评论发布即可见（落库 + 取回业务 id）+ 评论数/楼根回复数/热评索引同事务推进 */
    void saveVisibleCommentWithCount(PostCommentEntity commentEntity);

    /**
     * 根评论列表分页（root_id='0'）：
     * <ul>
     *   <li>{@code sort=hot}——单帖内按赞数降序，同数按 {@code create_time} 升序（发布时间先后）+ 业务 ID 升序，
     *       顺序来自根评论热评索引 {@code post_comment_feed_index}；索引只收已过审根评论，因此
     *       <b>本档只含 NORMAL 根评论</b>。</li>
     *   <li>其余（new）——按 {@code create_time} 升序 + 业务 ID 升序；NORMAL 公开。</li>
     * </ul>
     * total 与 offset 分页契约两档一致（total 恒为他人可见口径）。
     */
    List<PostCommentEntity> pageRootByPostId(String postId, String viewerId, String sort, int offset, int limit);

    /** 根评论真实总数：他人可见口径（仅 NORMAL） */
    long countVisibleRootsByPostId(String postId);

    /**
     * 楼内子回复平铺（root_id 归组、id 升序；NORMAL 公开；
     * 指向昵称 JOIN parent 行取、忽略其状态）
     */
    List<PostCommentEntity> pageRepliesByRootId(String rootId, String viewerId, int offset, int limit);

    /** 楼内可见子回复总数（与分页口径一致） */
    long countRepliesForThread(String rootId, String viewerId);

    /** 楼内 NORMAL 子回复总数（仅 NORMAL，供软删楼根/repry-count 维护） */
    long countRepliesByRootId(String rootId);

    /** 楼根 reply_count 变更（与子回复写入/状态变更同事务调用） */
    void updateReplyCount(String rootId, int delta);

    /** 取消评论点赞，返回 false = 未赞过（与点赞数变更同事务调用） */
    boolean removeCommentLike(String commentId, String userId);

    /**
     * 评论点赞数变更（与点赞记录写入/删除同事务调用，精确按影响行数 ±N；取消赞恒 -1）。
     * 落点是计数窄表 {@code post_comment_like_count}（真源，票 02）；命中 0 行 = 窄表行缺失的漂移，
     * 落 warn 交手工对账，不补行
     */
    void updateLikeCount(String commentId, int delta);

    /** 当前用户在给定评论集合中已点赞的评论 id（根评论与子回复通用） */
    List<String> selectLikedCommentIds(String userId, List<String> commentIdList);

    /** 按用户列评论分页（作者主页「评论」tab）：该用户 NORMAL 评论且源帖 NORMAL，按评论时间倒序 */
    List<PostCommentEntity> pageCommentsByUser(String userId, int offset, int limit);

    long countCommentsByUser(String userId);

    /** 评论详情；不同于 {@link #findPostById}，本条按评论 id 取 */
    PostCommentEntity findCommentById(String commentId);

    /** 帖子评论数变更（与评论写入同事务调用） */
    void updatePostCommentCount(String postId, int delta);

    /**
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
    void softDeleteCommentWithCount(String commentId, String postId, String rootId);

    /**
     * 评论点赞（合并写）：与帖子点赞同一条合并通道，{@code shardKey} 取评论主键。
     * 计数落窄表 {@code post_comment_like_count}（真源，与关系行同事务）；根评论的热评索引
     * like_count 快照同事务旁带——索引只服务排序，不是真值源。
     *
     * @return {@code LIKED} 已写入；{@code DUPLICATE} 已经赞过（计数未重复推进）
     */
    LikeStateVO likeCommentWithMergedCount(String commentId, String userId);

    /**
     * 取消评论点赞：仍走同事务精确 -1（不在本票的三条合并入口里，见 spec §3.1 的 SQL 清单）+
     * 唯一键/影响行数裁决，重复取消即抛 PARAM_ERROR 并回滚
     */
    void unlikeCommentWithCount(String commentId, String userId);

    /**
     * 热评索引全量重建（comment-count-narrow-table 票 03，兜底恢复手段：索引行与窄表漂移时手动触发）：
     * 单事务两条语句——清空 {@code post_comment_feed_index}（DELETE 不用 TRUNCATE，隐式提交会毁原子性）→
     * {@code INSERT ... SELECT} 重灌，行集 = {@code root_id = 0 AND state = 'NORMAL'} 的根评论。
     *
     * <p><b>只兜索引、不兜窄表</b>：{@code post_comment_like_count} 是计数真值源，重建不校准它。
     * 窄表缺行的根评论在重灌时按 0 处理（与「窄表无行即计数 0」同口径），且点赞也补不回窄表行
     * （票 02 的点赞命中 0 行只 warn、不造行）——别指望重建能修窄表。
     */
    void rebuildCommentFeedIndex();
}
