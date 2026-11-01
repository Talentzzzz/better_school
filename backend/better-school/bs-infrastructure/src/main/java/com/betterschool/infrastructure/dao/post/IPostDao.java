package com.betterschool.infrastructure.dao.post;

import com.betterschool.domain.community.model.valobj.FeedCursorVO;
import com.betterschool.domain.community.model.valobj.FeedIndexItemVO;
import com.betterschool.domain.community.model.valobj.PostReviewTimeoutVO;
import com.betterschool.infrastructure.dao.post.po.PostPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/PostMapper.xml
 */
@Mapper
public interface IPostDao {

    int insert(PostPO postPO);

    PostPO selectById(@Param("postId") String postId);

    /**
     * 详情读的计数查询（票 05 / spec §3.3）：按主键一次取回三个计数（like/comment 窄表 + 浏览 UV 加速列
     * post_view_count，feed-index-uv 票 02 起真相源在 user_view_history 明细表），
     * 每次读都查、在仓储拼回实体——详情缓存不存计数，缓存命中路径的三个数也恒等于库值。
     * 只填 likeCount / commentCount / viewCount 三列，其余字段为 null
     */
    PostPO selectCountsByPostId(@Param("postId") String postId);

    /** 覆盖草稿内容（仅 DRAFT 行生效） */
    int updateDraft(PostPO postPO);

    /** 提交草稿：DRAFT → PENDING_REVIEW（update_time 即进入待审时间），返回影响行数（1=本次刚提交） */
    int submitDraft(@Param("postId") String postId);

    /** 撤回待审：PENDING_REVIEW → DRAFT，返回影响行数 */
    int withdraw(@Param("postId") String postId);

    /** 被拒转草稿箱：REJECTED → DRAFT，返回影响行数 */
    int rejectToDraft(@Param("postId") String postId);

    /** 全量帖子 id（详情缓存布隆过滤器初始化用；一次性预热，刻意不设限） */
    List<String> selectAllIds();

    /** 搜索索引全量重建的游标页：post_id &gt; lastId 按 post_id 字典序取 limit 条 */
    List<String> selectIdsAfter(@Param("lastId") String lastId, @Param("limit") int limit);

    /** 按 id 列表查询（不分状态，搜索同步反查/搜索结果组装用） */
    List<PostPO> selectByIds(@Param("ids") List<String> postIds);

    int softDelete(@Param("postId") String postId);

    /** 审核通过：PENDING_REVIEW → NORMAL，返回影响行数（1=本次刚通过） */
    int approveById(@Param("postId") String postId);

    /** 审核驳回：PENDING_REVIEW → REJECTED，返回影响行数 */
    int rejectById(@Param("postId") String postId);

    /** 24h 待审超时提醒扫描：进入待审（update_time）早于 before 的待审帖子（部分列：post_id + 推导轮次） */
    List<PostReviewTimeoutVO> selectReviewTimeoutPosts(@Param("before") LocalDateTime before, @Param("limit") int limit);

    List<PostPO> selectPage(@Param("sort") String sort,
                            @Param("offset") int offset, @Param("limit") int limit);

    long count();

    /**
     * 主页 feed 索引序扫描（feed-index-uv 票 04）：只取 NORMAL，按 (排序键, post_id) DESC 索引序，
     * cursor 非首页时 {@code WHERE (排序键, post_id) < (?, ?)} 续页。部分列直映域 VO
     * （post_id + feed_time + like_count，与 selectReviewTimeoutPosts 同款）
     */
    List<FeedIndexItemVO> selectFeedIndex(@Param("sort") String sort, @Param("cursor") FeedCursorVO cursor,
                                          @Param("limit") int limit);

    /** 我的帖子分页（作者视角）：NORMAL/PENDING_REVIEW/REJECTED */
    List<PostPO> selectPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countByUser(@Param("userId") String userId);

    /** 我点赞过的帖子分页（按点赞时间倒序；点赞后帖子被驳回/待审仍向点赞者展示） */
    List<PostPO> selectLikedPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countLikedByUser(@Param("userId") String userId);

    /** 我评论过的帖子分页（去重，按最近评论时间倒序） */
    List<PostPO> selectCommentedPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countCommentedByUser(@Param("userId") String userId);

    /** 某用户的公开帖子分页（作者主页「笔记」tab）：仅 NORMAL，本人看自己也走此口径 */
    List<PostPO> selectNormalPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countNormalByUser(@Param("userId") String userId);

    /** 某用户赞过的帖子分页（作者主页「赞过」tab）：源帖仅 NORMAL，按点赞时间倒序 */
    List<PostPO> selectPublicLikedPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countPublicLikedByUser(@Param("userId") String userId);

    /**
     * 点赞关系行批量写（INSERT IGNORE 唯一键裁决重复，{@code post_id + user_id} 唯一）。
     *
     * @return 本次真正插入的行数 = 计数要加的量（撞键的行不计入，重复赞不重复计）
     */
    int insertLikes(@Param("postId") String postId, @Param("userIds") List<String> userIds);

    /** 取消赞批量删，返回真正删掉的行数 = 计数要回落的量 */
    int deleteLikes(@Param("postId") String postId, @Param("userIds") List<String> userIds);

    /** 批内撞键定位：这一批（post_id, user_id）里已经存在的 user_id（同事务内回查，不含批外的行） */
    List<String> selectLikeUserIds(@Param("postId") String postId, @Param("userIds") List<String> userIds);

    int existsLike(@Param("postId") String postId, @Param("userId") String userId);

    /** 当前用户已点赞的帖子 id 集合（列表 liked 状态批量回显） */
    List<String> selectLikedPostIds(@Param("userId") String userId, @Param("postIds") List<String> postIds);

    /** 点赞计数窄表 upsert（合并器一组一条：{@code like_count = like_count + N}） */
    int upsertLikeCount(@Param("postId") String postId, @Param("delta") int delta);

    /** 取消赞的计数回落（只 UPDATE 已存在的计数行，clamp 0 保证不为负） */
    int decreaseLikeCount(@Param("postId") String postId, @Param("delta") int delta);

    /**
     * 浏览 UV 明细批量写（合并器一组一条，{@code (user_id, post_id)} 主键裁决重复）。
     *
     * @return 本次真正插入的行数 = 新增 UV 数 = 加速列要加的量（重复浏览撞主键不计入）
     */
    int insertViewHistory(@Param("postId") String postId, @Param("userIds") List<String> userIds);

    /** 刷新本组浏览时刻（全部键，含刚插入的）：重复浏览的唯一可见效果就是 viewed_at 变新 */
    int touchViewedAt(@Param("postId") String postId, @Param("userIds") List<String> userIds);

    /** 浏览 UV 加速列 upsert（合并器一组一条：{@code view_count = view_count + 新增数}，行懒建） */
    int upsertViewCount(@Param("postId") String postId, @Param("delta") int delta);

    // ---------- 浏览历史的两个读能力（feed-index-uv 票 05：明细表即事实源） ----------

    /**
     * 我的浏览历史分页：user_view_history 二级索引 idx_user_viewed 按 viewed_at 倒序，
     * JOIN post 做可见性过滤（已删/被拒的他人帖不展示）并带出内容列/计数列/作者
     */
    List<PostPO> selectViewHistoryPage(@Param("userId") String userId, @Param("offset") int offset,
                                       @Param("limit") int limit);

    /** 浏览历史总数（与 {@link #selectViewHistoryPage} 同一可见性口径） */
    long countViewHistory(@Param("userId") String userId);

    /** 当前用户浏览过的帖子 id 集合（feed 已读灰显批量回显，走 (user_id, post_id) 主键） */
    List<String> selectViewedPostIds(@Param("userId") String userId, @Param("postIds") List<String> postIds);

    // ---------- feed 异构索引同事务直写（feed-index-uv 票 03） ----------

    /**
     * 审核通过的索引行诞生/翻回（ODKU upsert）：{@code feed_time} 只在 INSERT 侧落（行诞生时刻 =
     * 本次通过时刻），命中已有行只翻回 NORMAL、不刷 feed_time、不碰 like_count（赞合并事务独占维护）
     */
    int upsertFeedIndexOnApprove(@Param("postId") String postId);

    /**
     * 不可见化的 state 快照同步（驳回/撤回/被拒转草稿/软删共用一条）：行不存在（从未通过审）=
     * 0 行静默，不 upsert——未通过过的帖子不该凭空进索引
     */
    int updateFeedIndexState(@Param("postId") String postId, @Param("state") String state);

    /**
     * 赞合并事务旁路 ±N：纯 UPDATE 不 upsert（能被赞的帖子行必存在——NORMAL 才可赞，通过事务已建行）。
     *
     * @return 影响行数，0 = 索引行缺失的存量异常，调用方落日志交重建 job
     */
    int updateFeedIndexLikeCount(@Param("postId") String postId, @Param("delta") int delta);

    // ---------- feed 索引全量重建（feed-index-uv 票 07：兜底恢复手段，四条在一个事务内执行） ----------

    /** 重建第一步：清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉事务原子性） */
    int clearFeedIndex();

    /**
     * 重灌：一帖一行 1:1 投影。行集 = NORMAL 帖 ∪ 有过审批通过的帖（运行期语义：通过事务才诞生行，
     * 之后 state 跟随流转、行不消失）；feed_time = 审核记录最近一次 APPROVE 时刻（与票 01 回填脚本
     * 同口径），查不到审批流水取 create_time 兜底；like_count 从窄表抄入（无行 = 0）
     */
    int rebuildFeedIndexProjection();

    /** 浏览 UV 加速列校准第二步（第一步 = {@link #recalibrateViewCounts}）：清掉没有明细行的孤儿计数行（懒建行口径：无浏览不建行） */
    int deleteViewCountsMissingHistory();

    /** 重灌：post_view_count 重投影 = user_view_history 行数（ODKU，缺行补建 / 漂移刷新 / 幂等） */
    int recalibrateViewCounts();
}
