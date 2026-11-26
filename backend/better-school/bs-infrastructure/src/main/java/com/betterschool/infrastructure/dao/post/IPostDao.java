package com.betterschool.infrastructure.dao.post;

import com.betterschool.infrastructure.dao.post.po.PostFeedIndexRow;
import com.betterschool.infrastructure.dao.post.po.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子 Mapper（post 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostMapper.xml。
 * 全部语句纯 post 单表——计数、作者昵称头像、关系列表的帖子行均由仓储多查一次拼装（SQL 层零 JOIN）；
 * 计数窄表 / 关系表 / 浏览历史 / feed 索引见同包 {@link IPostLikeDao} {@link IPostLikeCountDao}
 * {@link IPostViewCountDao} {@link IUserViewHistoryDao} {@link IPostFeedIndexDao}，
 * post_comment_count 见 dao.comment 包 {@link com.betterschool.infrastructure.dao.comment.IPostCommentCountDao}
 */
@Mapper
public interface IPostDao {

    int insert(Post post);

    /** 详情内容查询（票 05 读路径拆两段之一）：纯 post 行，进缓存；计数与作者信息仓储回查，不进缓存 */
    Post selectById(@Param("postId") String postId);

    /** 覆盖草稿内容（仅 DRAFT 行生效） */
    int updateDraft(Post post);

    /** 提交草稿：DRAFT → NORMAL，返回影响行数（1=本次刚提交） */
    int submitDraft(@Param("postId") String postId);

    /**
     * 读回数据库时钟的 {@code update_time}（发帖返利周期键的发布时刻）：插入/提交后在同一事务内调用。
     * 不复用 {@link #selectById}——它的列片段不含 {@code update_time}
     */
    LocalDateTime selectUpdateTime(@Param("postId") String postId);

    /** 全量帖子 id（详情缓存布隆过滤器初始化用；一次性预热，刻意不设限） */
    List<String> selectAllIds();

    /** 搜索索引全量重建的游标页：post_id &gt; lastId 按 post_id 字典序取 limit 条 */
    List<String> selectIdsAfter(@Param("lastId") String lastId, @Param("limit") int limit);

    /** 按 id 列表查询（不分状态，搜索同步反查/搜索结果组装/关系列表回查用） */
    List<Post> selectByIds(@Param("ids") List<String> postIdList);

    int softDelete(@Param("postId") String postId);

    /** 旧直查降级路径 time 档：NORMAL 按 (create_time, post_id) 倒序 SQL 分页（hot 档走 {@link #selectNormalRows} 内存排序） */
    List<Post> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    /** 旧直查降级路径 hot 档取数：NORMAL 全集（无 LIMIT），窄表批量回查后仓储内存排序切片 */
    List<Post> selectNormalRows();

    long count();

    /** 我的帖子分页（作者视角）：NORMAL/DRAFT */
    List<Post> selectPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countByUser(@Param("userId") String userId);

    /** 某用户的公开帖子分页（作者主页「笔记」tab）：仅 NORMAL，本人看自己也走此口径 */
    List<Post> selectNormalPageByUser(@Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);

    long countNormalByUser(@Param("userId") String userId);

    /** 某用户全部 NORMAL 帖 id 集（获赞统计取数①，UserRepository 回查 like_count 窄表求和） */
    List<String> selectNormalIdsByUser(@Param("userId") String userId);

    /** 重灌取数①：post 全量 (post_id, state, create_time)——仓储按 state 裁行集后与计数拼装 */
    List<PostFeedIndexRow> selectRebuildRows();
}
