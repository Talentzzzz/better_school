package com.betterschool.infrastructure.dao.post;

import com.betterschool.infrastructure.dao.post.po.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子浏览 UV 加速列 Mapper（post_view_count 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostViewCountMapper.xml
 */
@Mapper
public interface IPostViewCountDao {

    /** 计数窄表批量读（票 05 拆两段之一的批量形态）：单表 IN 查询，仓储 COALESCE 0 拼回 PO。只填 postId + view_count 列 */
    List<Post> selectViewCountsByPostIds(@Param("postIds") List<String> postIdList);

    /** 浏览 UV 加速列 upsert（合并器一组一条：{@code view_count = view_count + 新增数}，行懒建） */
    int upsertViewCount(@Param("postId") String postId, @Param("delta") int delta);

    /** 浏览 UV 加速列校准第一步（第二步 = {@link #recalibrateViewCounts}）：清掉没有明细行的孤儿计数行（懒建行口径：无浏览不建行） */
    int deleteViewCountsMissingHistory();

    /** 重灌：post_view_count 重投影 = user_view_history 行数（ODKU，缺行补建 / 漂移刷新 / 幂等） */
    int recalibrateViewCounts();
}
