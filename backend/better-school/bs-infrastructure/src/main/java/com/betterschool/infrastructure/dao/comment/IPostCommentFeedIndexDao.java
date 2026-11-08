package com.betterschool.infrastructure.dao.comment;

import com.betterschool.infrastructure.dao.comment.po.CommentFeedIndexRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 根评论热评索引 Mapper（post_comment_feed_index 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostCommentFeedIndexMapper.xml
 */
@Mapper
public interface IPostCommentFeedIndexDao {

    /** 最热档第一步：根评论索引序扫描，出本页 comment_id 序（like_count 降序、同数 create_time 升序 + comment_id 升序） */
    List<String> selectRootFeedCommentIds(@Param("postId") String postId,
                                          @Param("offset") int offset, @Param("limit") int limit);

    // ---------- 根评论热评索引同事务直写（票 01：索引行与评论行同库，审核 / 赞合并 / 软删事务带写） ----------

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

    // ---------- 热评索引全量重建（票 03：兜底恢复手段，两条在一个事务内执行） ----------

    /** 重建第一步：清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉事务原子性） */
    int clearFeedIndex();

    /** 重灌写回：仓储拼装好的行集批量 INSERT */
    int insertFeedIndexRows(@Param("rows") List<CommentFeedIndexRow> feedIndexRowList);
}
