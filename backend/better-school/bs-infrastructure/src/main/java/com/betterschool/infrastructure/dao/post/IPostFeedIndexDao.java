package com.betterschool.infrastructure.dao.post;

import com.betterschool.domain.post.model.valobj.FeedCursorVO;
import com.betterschool.domain.post.model.valobj.FeedIndexItemVO;
import com.betterschool.infrastructure.dao.post.po.PostFeedIndexRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 帖子 feed 异构索引 Mapper（post_feed_index 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/PostFeedIndexMapper.xml
 */
@Mapper
public interface IPostFeedIndexDao {

    /**
     * 主页 feed 索引序扫描（feed-index-uv 票 04）：只取 NORMAL，按 (排序键, post_id) DESC 索引序，
     * cursor 非首页时 {@code WHERE (排序键, post_id) < (?, ?)} 续页。部分列直映域 VO
     * （post_id + feed_time + like_count，与审核超时扫描同款）
     */
    List<FeedIndexItemVO> selectFeedIndex(@Param("sort") String sort, @Param("cursor") FeedCursorVO feedCursorVO,
                                          @Param("limit") int limit);

    // ---------- feed 异构索引同事务直写（feed-index-uv 票 03） ----------

    /**
     * 审核通过的索引行诞生/翻回（ODKU upsert）：{@code feed_time} 只在 INSERT 侧落（行诞生时刻 =
     * 本次通过时刻），命中已有行只翻回 NORMAL、不刷 feed_time、不碰 like_count（赞合并事务独占维护）
     */
    int upsertFeedIndexOnApprove(@Param("postId") String postId);

    /**
     * 不可见化的 state 快照同步（驳回/撤回/被拒转草稿/软删共用一条）：行不存在（从未通过审）=
     * 0 行静默，不 upsert——未通过过的帖子不该凭空进索引
     */
    int updateFeedIndexState(@Param("postId") String postId, @Param("state") String state);

    /**
     * 赞合并事务旁路 ±N：纯 UPDATE 不 upsert（能被赞的帖子行必存在——NORMAL 才可赞，通过事务已建行）。
     *
     * @return 影响行数，0 = 索引行缺失的存量异常，调用方落日志交重建 job
     */
    int updateFeedIndexLikeCount(@Param("postId") String postId, @Param("delta") int delta);

    // ---------- feed 索引全量重建（feed-index-uv 票 07：兜底恢复手段，各步在一个事务内执行） ----------

    /** 重建第一步：清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉事务原子性） */
    int clearFeedIndex();

    /** 重灌写回：仓储拼装好的行集批量 INSERT（feed_time = 最近 APPROVE 时刻 ?? create_time，like_count 从窄表抄入） */
    int insertFeedIndexRows(@Param("rows") List<PostFeedIndexRow> feedIndexRowList);
}
