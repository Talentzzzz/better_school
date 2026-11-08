package com.betterschool.infrastructure.dao.comment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评论点赞关系 Mapper（post_comment_like 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostCommentLikeMapper.xml
 */
@Mapper
public interface IPostCommentLikeDao {

    /**
     * 评论点赞批量写（{@code INSERT IGNORE}，{@code comment_id + user_id} 唯一）。
     *
     * @return 本次真正插入的行数 = 计数窄表 like_count 要加的量（重复赞不重复计）
     */
    int insertCommentLikes(@Param("commentId") String commentId, @Param("userIds") List<String> userIdList);

    /** 批内撞键定位：这一批（comment_id, user_id）里已经存在的 user_id */
    List<String> selectCommentLikeUserIds(@Param("commentId") String commentId, @Param("userIds") List<String> userIdList);

    /** 取消评论点赞（单行删除；取消仍走同事务精确 -1，不经合并器） */
    int deleteCommentLike(@Param("commentId") String commentId, @Param("userId") String userId);

    /** 当前用户在给定评论集合中已点赞的评论 id（根评论与子回复通用） */
    List<String> selectLikedCommentIds(@Param("userId") String userId, @Param("commentIds") List<String> commentIdList);
}
