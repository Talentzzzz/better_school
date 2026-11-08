package com.betterschool.infrastructure.dao.comment;

import com.betterschool.infrastructure.dao.post.po.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子评论计数窄表 Mapper（post_comment_count 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostCommentCountMapper.xml
 */
@Mapper
public interface IPostCommentCountDao {

    /** 计数窄表批量读：单表 IN 查询，仓储 COALESCE 0 拼回 PO（详情与列表共用）。只填 postId + comment_count 列 */
    List<Post> selectCommentCountsByPostIds(@Param("postIds") List<String> postIdList);

    /** 评论计数窄表 upsert（评论通过 +1 / 软删 -1，只计 NORMAL 态；同事务调用，不再锁 post 行） */
    int upsertPostCommentCount(@Param("postId") String postId, @Param("delta") int delta);
}
