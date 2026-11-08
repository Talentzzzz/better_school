package com.betterschool.infrastructure.dao.post;

import com.betterschool.infrastructure.dao.post.po.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子点赞计数窄表 Mapper（post_like_count 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostLikeCountMapper.xml
 */
@Mapper
public interface IPostLikeCountDao {

    /**
     * 计数窄表批量读（票 05 拆两段之一的批量形态）：单表 IN 查询，仓储 COALESCE 0 拼回 PO——
     * 详情（单元素）与列表（页内 id 集）共用。只填 postId + like_count 列，其余字段为 null
     */
    List<Post> selectLikeCountsByPostIds(@Param("postIds") List<String> postIdList);

    /** 点赞计数窄表 upsert（合并器一组一条：{@code like_count = like_count + N}） */
    int upsertLikeCount(@Param("postId") String postId, @Param("delta") int delta);

    /** 取消赞的计数回落（只 UPDATE 已存在的计数行，clamp 0 保证不为负） */
    int decreaseLikeCount(@Param("postId") String postId, @Param("delta") int delta);
}
