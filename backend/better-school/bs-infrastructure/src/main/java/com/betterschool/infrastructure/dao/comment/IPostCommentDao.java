package com.betterschool.infrastructure.dao.comment;

import com.betterschool.infrastructure.dao.comment.po.CommentFeedIndexRow;
import com.betterschool.infrastructure.dao.comment.po.PostComment;
import com.betterschool.infrastructure.dao.post.po.PostRelationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子评论 Mapper（post_comment 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostCommentMapper.xml。
 * 评论点赞关系 / 点赞计数窄表 / 热评索引 / 评论计数窄表见同包 {@link IPostCommentLikeDao}
 * {@link IPostCommentLikeCountDao} {@link IPostCommentFeedIndexDao} {@link IPostCommentCountDao}
 */
@Mapper
public interface IPostCommentDao {

    int insert(PostComment postComment);

    /**
     * 根评论列表分页默认档（root_id='0'；NORMAL 公开 + viewerId 本人待审/未通过可见；按 create_time + 业务 ID 升序）。
     * 最热档不走这条，见 {@link IPostCommentFeedIndexDao#selectRootFeedCommentIds}
     * + {@link #selectNormalRootsByIds}
     */
    List<PostComment> selectRootPageByPostId(@Param("postId") String postId, @Param("viewerId") String viewerId,
                                               @Param("offset") int offset, @Param("limit") int limit);

    /** 最热档第二步：按 id 集回查 NORMAL 根评论行（纯单表，不排序；昵称/驳回理由仓储拼装） */
    List<PostComment> selectNormalRootsByIds(@Param("commentIds") List<String> commentIdList);

    /** 根评论真实总数：他人可见口径（仅 NORMAL 根评论，不含待审/未通过） */
    long countVisibleRootsByPostId(@Param("postId") String postId);

    /**
     * 楼内子回复平铺（root_id 归组、id 升序；NORMAL 公开 + viewerId 本人待审/未通过；
     * 指向评论行与指向昵称由仓储按 parent_id 集回查拼装、忽略其状态）
     */
    List<PostComment> selectRepliesByRootId(@Param("rootId") String rootId, @Param("viewerId") String viewerId,
                                              @Param("offset") int offset, @Param("limit") int limit);

    /** 楼内可见子回复总数（viewerId 非空时含其本人待审/未通过） */
    long countRepliesForThread(@Param("rootId") String rootId, @Param("viewerId") String viewerId);

    /** 楼内 NORMAL 子回复总数（仅 NORMAL，供软删楼根/统计维护） */
    long countRepliesByRootId(@Param("rootId") String rootId);

    /**
     * 按用户列评论（作者主页「评论」tab）：该用户的全部 NORMAL 评论行（单表，按评论时间倒序）；
     * 源帖 NORMAL 过滤与来源帖标题/封面由仓储回查 post 拼装（total = 过滤后集合大小）
     */
    List<PostComment> selectNormalRowsByUser(@Param("userId") String userId);

    /** 单条评论（纯 post_comment 单表；点赞/审核前置检查、详情组装用） */
    PostComment selectById(@Param("commentId") String commentId);

    /** 按 id 集批量回查（不分状态：楼内指向评论忽略父行状态；审核队列素材回查按 state 过滤在调用方） */
    List<PostComment> selectByIds(@Param("ids") List<String> commentIdList);

    /** 楼根 reply_count 变更（调用方保证同事务） */
    int updateReplyCount(@Param("rootId") String rootId, @Param("delta") int delta);

    /** 「我评论过的」关系行主查（post_comment 单表去重，按最近评论时间倒序）；帖子行与可见性仓储回查收口 */
    List<PostRelationRow> selectRelationCommentedRows(@Param("userId") String userId);

    /** 审核通过：PENDING_REVIEW → NORMAL，返回影响行数（1=本次刚通过，评论数 +1 依据） */
    int approveById(@Param("commentId") String commentId);

    /** 审核驳回：PENDING_REVIEW → REJECTED（终态，从未计数），返回影响行数 */
    int rejectById(@Param("commentId") String commentId);

    /** 统一软删：仅指定前置状态翻转为 DELETED（用户删除与举报下架同轨），返回影响行数 */
    int markDeletedFromStatus(@Param("commentId") String commentId, @Param("fromStatus") String fromStatus);

    /** 评论所在楼根业务 ID：'0' = 根评论（入索引），其余 = 子回复（不入索引）；无此行返回 null */
    String selectRootId(@Param("commentId") String commentId);

    /**
     * 重灌取数：根评论行集 = {@code root_id = '0' AND state = 'NORMAL'}（comment_id + post_id + create_time）。
     * {@code create_time} 最终值 = 审核记录最近一次 APPROVE 时刻（查不到审批流水的存量行取评论 create_time 兜底），
     * {@code like_count} 从计数窄表抄入（无行 = 0）——拼装在仓储、写回走
     * {@link IPostCommentFeedIndexDao#insertFeedIndexRows}。
     * 只兜索引、不兜窄表——窄表是真值源，重建不改写它
     */
    List<CommentFeedIndexRow> selectRebuildRows();
}
