package com.betterschool.infrastructure.dao.post;

import com.betterschool.infrastructure.dao.post.po.PostRelationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子点赞关系 Mapper（post_like 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostLikeMapper.xml
 */
@Mapper
public interface IPostLikeDao {

    /**
     * 「我赞过的 / 某用户赞过的」关系行主查（post_like 单表，按点赞时间倒序）：分页、可见性过滤
     * （自己赞的：NORMAL；他人主页：仅 NORMAL）由仓储二次回查后 Java 侧收口
     */
    List<PostRelationRow> selectRelationLikedRows(@Param("userId") String userId);

    /**
     * 点赞关系行批量写（INSERT IGNORE 唯一键裁决重复，{@code post_id + user_id} 唯一）。
     *
     * @return 本次真正插入的行数 = 计数要加的量（撞键的行不计入，重复赞不重复计）
     */
    int insertLikes(@Param("postId") String postId, @Param("userIds") List<String> userIdList);

    /** 取消赞批量删，返回真正删掉的行数 = 计数要回落的量 */
    int deleteLikes(@Param("postId") String postId, @Param("userIds") List<String> userIdList);

    /** 批内撞键定位：这一批（post_id, user_id）里已经存在的 user_id（同事务内回查，不含批外的行） */
    List<String> selectLikeUserIds(@Param("postId") String postId, @Param("userIds") List<String> userIdList);

    int existsLike(@Param("postId") String postId, @Param("userId") String userId);

    /** 当前用户已点赞的帖子 id 集合（列表 liked 状态批量回显） */
    List<String> selectLikedPostIds(@Param("userId") String userId, @Param("postIds") List<String> postIdList);
}
