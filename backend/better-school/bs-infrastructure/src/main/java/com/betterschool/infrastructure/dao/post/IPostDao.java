package com.betterschool.infrastructure.dao.post;

import com.betterschool.domain.community.model.valobj.FeedCursorVO;
import com.betterschool.domain.community.model.valobj.FeedIndexItemVO;
import com.betterschool.domain.community.model.valobj.PostReviewTimeoutVO;
import com.betterschool.infrastructure.dao.post.po.PostFeedIndexRowPO;
import com.betterschool.infrastructure.dao.post.po.PostPO;
import com.betterschool.infrastructure.dao.post.po.PostRelationRowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/PostMapper.xml。
 * 全部语句纯 post / 窄表 / 关系表单表——计数、作者昵称头像、关系列表的帖子行均由仓储多查一次拼装（SQL 层零 JOIN）
 */
@Mapper
public interface IPostDao {

    int insert(PostPO postPO);

    /** 详情内容查询（票 05 读路径拆两段之一）：纯 post 行，进缓存；计数与作者信息仓储回查，不进缓存 */
    PostPO selectById(@Param("postId") String postId);

    /**
     * 计数窄表批量读（票 05 拆两段之二的批量形态）：三张窄表各一次单表 IN 查询，仓储 COALESCE 0 拼回 PO——
     * 详情（单元素）与列表（页内 id 集）共用。只填 postId + 对应计数列，其余字段为 null
     */
    List<PostPO> selectLikeCountsByPostIds(@Param("postIds") List<String> postIds);

    List<PostPO> selectCommentCountsByPostIds(@Param("postIds") List<String> postIds);

    List<PostPO> selectViewCountsByPostIds(@Param("postIds") List<String> postIds);

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

    /** 按 id 列表查询（不分状态，搜索同步反查/搜索结果组装/关系列表回查用） */
    List<PostPO> selectByIds(@Param("ids") List<String> postIds);

    int softDelete(@Param("postId") String postId);

    /** 审核通过：PENDING_REVIEW → NORMAL，返回影响行数（1=本次刚通过） */
    int approveById(@Param("postId") String postId);

    /** 审核驳回：PENDING_REVIEW → REJECTED，返回影响行数 */
    int rejectById(@Param("postId") String postId);

    /** 24h 待审超时提醒扫描：进入待审（update_time）早于 before 的待审帖子（部分列：post_id + 推导轮次） */
    List<PostReviewTimeoutVO> selectReviewTimeoutPosts(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 旧直查降级路径 time 档：NORMAL 按 (create_time, post_id) 倒序 SQL 分页（hot 档走 {@link #selectNormalRows} 内存排序） */
    List<PostPO> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    /** 旧直查降级路径 hot 档取数：NORMAL 全集（无 LIMIT），窄表批量回查后仓储内存排序切片 */
    List<PostPO> selectNormalRows();

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

    /** 某用户的公开帖子分页（作者主页「笔记」tab）：仅 NORMAL，本人看自己也走此口径 */
    List<PostPO> selectNormalPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countNormalByUser(@Param("userId") String userId);

    /** 某用户全部 NORMAL 帖 id 集（获赞统计取数①，UserRepository 回查 like_count 窄表求和） */
    List<String> selectNormalIdsByUser(@Param("userId") String userId);

    /**
     * 「我赞过的 / 某用户赞过的」关系行主查（post_like 单表，按点赞时间倒序）：分页、可见性过滤
     * （自己赞的：NORMAL/PENDING_REVIEW/REJECTED；他人主页：仅 NORMAL）由仓储二次回查后 Java 侧收口
     */
    List<PostRelationRowPO> selectRelationLikedRows(@Param("userId") String userId);

    /** 「我评论过的」关系行主查（post_comment 单表去重，按最近评论时间倒序）；帖子行与可见性仓储回查收口 */
    List<PostRelationRowPO> selectRelationCommentedRows(@Param("userId") String userId);

    /** 「浏览历史」明细行主查（user_view_history 单表，viewed_at 倒序）；帖子行与可见性仓储回查收口 */
    List<PostRelationRowPO> selectRelationViewedRows(@Param("userId") String userId);

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

    // ---------- feed 索引全量重建（feed-index-uv 票 07：兜底恢复手段，各步在一个事务内执行） ----------

    /** 重建第一步：清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉事务原子性） */
    int clearFeedIndex();

    /** 重灌取数①：post 全量 (post_id, state, create_time)——行集 = NORMAL ∪ 有过审批通过，仓储与 ②③ 拼装 */
    List<PostFeedIndexRowPO> selectRebuildRows();

    /** 重灌写回：仓储拼装好的行集批量 INSERT（feed_time = 最近 APPROVE 时刻 ?? create_time，like_count 从窄表抄入） */
    int insertFeedIndexRows(@Param("rows") List<PostFeedIndexRowPO> rows);

    /** 浏览 UV 加速列校准第二步（第一步 = {@link #recalibrateViewCounts}）：清掉没有明细行的孤儿计数行（懒建行口径：无浏览不建行） */
    int deleteViewCountsMissingHistory();

    /** 重灌：post_view_count 重投影 = user_view_history 行数（ODKU，缺行补建 / 漂移刷新 / 幂等） */
    int recalibrateViewCounts();
}
