package com.betterschool.infrastructure.dao.post;

import com.betterschool.infrastructure.dao.post.po.PostRelationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子浏览历史明细 Mapper（user_view_history 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/UserViewHistoryMapper.xml
 */
@Mapper
public interface IUserViewHistoryDao {

    /**
     * 浏览 UV 明细批量写（合并器一组一条，{@code (user_id, post_id)} 主键裁决重复）。
     *
     * @return 本次真正插入的行数 = 新增 UV 数 = 加速列要加的量（重复浏览撞主键不计入）
     */
    int insertViewHistory(@Param("postId") String postId, @Param("userIds") List<String> userIdList);

    /** 刷新本组浏览时刻（全部键，含刚插入的）：重复浏览的唯一可见效果就是 viewed_at 变新 */
    int touchViewedAt(@Param("postId") String postId, @Param("userIds") List<String> userIdList);

    /** 「浏览历史」明细行主查（user_view_history 单表，viewed_at 倒序）；帖子行与可见性仓储回查收口 */
    List<PostRelationRow> selectRelationViewedRows(@Param("userId") String userId);

    /** 当前用户浏览过的帖子 id 集合（feed 已读灰显批量回显，走 (user_id, post_id) 主键） */
    List<String> selectViewedPostIds(@Param("userId") String userId, @Param("postIds") List<String> postIdList);
}
