package com.betterschool.infrastructure.dao.moderation;

import com.betterschool.infrastructure.dao.moderation.po.ContentReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审核动作记录表 Mapper（content_review 单表），
 * SQL 定义在 app 模块 resources/mybatis/mapper/ContentReviewMapper.xml
 */
@Mapper
public interface IContentReviewDao {

    /** INSERT IGNORE：uk_target_action 冲突 = 已记录过（幂等，审计只插一次） */
    int insertIgnoreReview(ContentReview review);

    /** 同一内容历轮裁决（review_round 升序） */
    List<ContentReview> selectReviewsByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 同一作者历史裁决（单表 author_id 直查免 join，id 降序取最新 limit 条） */
    List<ContentReview> selectReviewsByAuthor(@Param("authorId") String authorId, @Param("limit") int limit);

    /** 拒绝理由：uk_target_action 下 REJECT 至多一行，取最新一条兜底 */
    String selectRejectReason(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 批量拒绝理由取数：该批 target 的全部 REJECT 行（含物理 id），「每 target 取最新」裁决在仓储 Java 侧做 */
    List<ContentReview> selectRejectRowsByTargetIds(@Param("targetType") String targetType,
                                                      @Param("targetIds") List<String> targetIdList);

    /** 某类型全部 APPROVE 流水的最新时刻（content_review 单表 GROUP BY，feed 索引重建拼 feed_time 用） */
    List<ContentReview> selectApproveRowsByTargetType(@Param("targetType") String targetType);
}
