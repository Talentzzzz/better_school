package com.betterschool.infrastructure.dao.comment;

import com.betterschool.infrastructure.dao.comment.po.PostCommentLikeCountPO;
import com.betterschool.infrastructure.dao.comment.po.PostCommentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子评论 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/PostCommentMapper.xml
 */
@Mapper
public interface IPostCommentDao {

    int insert(PostCommentPO postCommentPO);

    /**
     * 根评论列表分页默认档（root_id='0'；NORMAL 公开 + viewerId 本人待审/未通过可见；按 create_time + 业务 ID 升序）。
     * 最热档不走这条，见 {@link #selectRootFeedCommentIds} + {@link #selectNormalRootsByIds}
     */
    List<PostCommentPO> selectRootPageByPostId(@Param("postId") String postId, @Param("viewerId") String viewerId,
                                               @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 最热档第一步：根评论索引序扫描，出本页 comment_id 序（like_count 降序、同数 create_time 升序 + comment_id 升序）。
     * 索引行集 = 当前可见根评论，因此本档天然不含待审/驳回的自见行
     */
    List<String> selectRootFeedCommentIds(@Param("postId") String postId,
                                          @Param("offset") int offset, @Param("limit") int limit);

    /** 最热档第二步：按 id 集回查 NORMAL 根评论行（保留昵称 / 驳回理由 JOIN，不排序） */
    List<PostCommentPO> selectNormalRootsByIds(@Param("commentIds") List<String> commentIds);

    /** 根评论真实总数：他人可见口径（仅 NORMAL 根评论，不含待审/未通过） */
    long countVisibleRootsByPostId(@Param("postId") String postId);

    /**
     * 楼内子回复平铺（root_id 归组、id 升序；NORMAL 公开 + viewerId 本人待审/未通过；
     * 指向昵称 JOIN parent 行取、忽略其状态）
     */
    List<PostCommentPO> selectRepliesByRootId(@Param("rootId") String rootId, @Param("viewerId") String viewerId,
                                              @Param("offset") int offset, @Param("limit") int limit);

    /** 楼内可见子回复总数（viewerId 非空时含其本人待审/未通过） */
    long countRepliesForThread(@Param("rootId") String rootId, @Param("viewerId") String viewerId);

    /** 楼内 NORMAL 子回复总数（仅 NORMAL，供软删楼根/统计维护） */
    long countRepliesByRootId(@Param("rootId") String rootId);

    /**
     * 按用户列评论分页（作者主页「评论」tab）：该用户 NORMAL 评论且源帖 NORMAL，
     * JOIN post 取来源帖标题/封面，按评论时间倒序
     */
    List<PostCommentPO> selectPageByUser(@Param("userId") String userId,
                                         @Param("offset") int offset, @Param("limit") int limit);

    long countByUser(@Param("userId") String userId);

    /** 楼根 reply_count 变更（调用方保证同事务） */
    int updateReplyCount(@Param("rootId") String rootId, @Param("delta") int delta);

    /**
     * 评论点赞批量写（{@code INSERT IGNORE}，{@code comment_id + user_id} 唯一）。
     *
     * @return 本次真正插入的行数 = 计数窄表 like_count 要加的量（重复赞不重复计）
     */
    int insertCommentLikes(@Param("commentId") String commentId, @Param("userIds") List<String> userIds);

    /** 批内撞键定位：这一批（comment_id, user_id）里已经存在的 user_id */
    List<String> selectCommentLikeUserIds(@Param("commentId") String commentId, @Param("userIds") List<String> userIds);

    /** 取消评论点赞（单行删除；取消仍走同事务精确 -1，不经合并器） */
    int deleteCommentLike(@Param("commentId") String commentId, @Param("userId") String userId);

    /**
     * 评论点赞数变更：落<b>计数窄表</b> {@code post_comment_like_count}（票 02 起的真源），
     * 点赞走合并器（+本组影响行数）、取消赞同步同事务 -1，两处都 clamp 0；主行 {@code update_time} 不再被刷。
     *
     * @return 命中行数；0 = 窄表行缺失（调用方落 warn 交手工对账，不补行）
     */
    int updateLikeCount(@Param("commentId") String commentId, @Param("delta") int delta);

    /** 当前用户在给定评论集合中已点赞的评论 id（根评论与子回复通用） */
    List<String> selectLikedCommentIds(@Param("userId") String userId, @Param("commentIds") List<String> commentIds);

    // ---------- 评论点赞计数窄表（post_comment_like_count，票 02 起的真源） ----------

    /**
     * 审核通过：计数行诞生（{@code INSERT IGNORE}，赞数恒 0；根评论与子回复都入表）。
     *
     * @return 影响行数；0 = 行已存在（漂移，调用方落 warn 不覆盖既有计数）
     */
    int insertLikeCountOnApprove(@Param("commentId") String commentId);

    /**
     * 展示取数：按页内评论 id 集一次回查窄表（列表/楼内/主页 tab/单行同一通路）。
     * 缺行由调用方补 0（存量语义），因此返回行数可能少于入参 id 数
     */
    List<PostCommentLikeCountPO> selectLikeCountsByIds(@Param("commentIds") List<String> commentIds);

    PostCommentPO selectById(@Param("commentId") String commentId);

    /** 评论计数窄表 upsert（评论通过 +1 / 软删 -1，只计 NORMAL 态；同事务调用，不再锁 post 行） */
    int upsertPostCommentCount(@Param("postId") String postId, @Param("delta") int delta);

    /** 审核通过：PENDING_REVIEW → NORMAL，返回影响行数（1=本次刚通过，评论数 +1 依据） */
    int approveById(@Param("commentId") String commentId);

    /** 审核驳回：PENDING_REVIEW → REJECTED（终态，从未计数），返回影响行数 */
    int rejectById(@Param("commentId") String commentId);

    /** 统一软删：仅指定前置状态翻转为 DELETED（用户删除与举报下架同轨），返回影响行数 */
    int markDeletedFromStatus(@Param("commentId") String commentId, @Param("fromStatus") String fromStatus);

    // ---------- 根评论热评索引（post_comment_feed_index，同事务直写） ----------

    /** 根评论审核通过：索引行诞生，赞数恒 0（待审态不可被赞）；调用方按 root_id 分流，子回复不入索引 */
    int insertFeedIndexOnApprove(@Param("commentId") String commentId, @Param("postId") String postId);

    /**
     * 索引 like_count 快照旁带 ±N（GREATEST 兜底不为负）。
     *
     * @return 命中行数；0 = 索引行缺失（调用方落 warn 交重建 job），子回复必然 0 行
     */
    int updateFeedIndexLikeCount(@Param("commentId") String commentId, @Param("delta") int delta);

    /** 根评论软删：索引行物理退场（行集 = 当前可见根评论，评论软删是终态、无恢复路径） */
    int deleteFeedIndex(@Param("commentId") String commentId);

    /** 评论所在楼根业务 ID：'0' = 根评论（入索引），其余 = 子回复（不入索引）；无此行返回 null */
    String selectRootId(@Param("commentId") String commentId);

    // ---------- 热评索引全量重建（票 03：兜底恢复手段，两条在一个事务内执行） ----------

    /** 重建第一步：清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉事务原子性） */
    int clearFeedIndex();

    /**
     * 重灌：根评论一行 1:1 投影，行集 = {@code root_id = '0' AND state = 'NORMAL'}。
     * {@code create_time} = 审核记录最近一次 APPROVE 时刻（查不到审批流水的存量行取评论 create_time 兜底），
     * {@code like_count} 从计数窄表抄入（无行 = 0）。只兜索引、不兜窄表——窄表是真值源，重建不改写它
     */
    int rebuildFeedIndexProjection();
}
