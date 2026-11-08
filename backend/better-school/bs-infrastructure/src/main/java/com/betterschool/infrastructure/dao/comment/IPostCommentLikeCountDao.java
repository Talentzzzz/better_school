package com.betterschool.infrastructure.dao.comment;

import com.betterschool.infrastructure.dao.comment.po.PostCommentLikeCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评论点赞计数窄表 Mapper（post_comment_like_count 单表，票 02 起的计数真源）：
 * SQL 定义在 app 模块 resources/mybatis/mapper/PostCommentLikeCountMapper.xml
 */
@Mapper
public interface IPostCommentLikeCountDao {

    /**
     * 审核通过：计数行诞生（{@code INSERT IGNORE}，赞数恒 0；根评论与子回复都入表）。
     *
     * @return 影响行数；0 = 行已存在（漂移，调用方落 warn 不覆盖既有计数）
     */
    int insertLikeCountOnApprove(@Param("commentId") String commentId);

    /**
     * 评论点赞数变更：落<b>计数窄表</b> {@code post_comment_like_count}（票 02 起的真源），
     * 点赞走合并器（+本组影响行数）、取消赞同步同事务 -1，两处都 clamp 0；主行 {@code update_time} 不再被刷。
     *
     * @return 命中行数；0 = 窄表行缺失（调用方落 warn 交手工对账，不补行）
     */
    int updateLikeCount(@Param("commentId") String commentId, @Param("delta") int delta);

    /**
     * 展示取数：按页内评论 id 集一次回查窄表（列表/楼内/主页 tab/单行同一通路）。
     * 缺行由调用方补 0（存量语义），因此返回行数可能少于入参 id 数
     */
    List<PostCommentLikeCount> selectLikeCountsByIds(@Param("commentIds") List<String> commentIdList);
}
