package com.betterschool.domain.moderation.model.entity;

import com.betterschool.domain.moderation.model.valobj.ReportStatusVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import lombok.Data;

import java.time.LocalDateTime;

/** 举报单（uk(reporter_id,target_type,target_id) 一人一内容一单；author_id 冗余防处理时内容已删） */
@Data
public class ContentReportEntity {

    private Long id;
    private ReviewTargetTypeVO targetType;
    private String targetId;
    private String authorId;
    private String reporterId;
    private String reason;
    private String contentSnapshot;
    private ReportStatusVO status;
    private String handlerId;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;

    /** 查询冗余字段：举报人/被举报人昵称（列表 JOIN 填充） */
    private String reporterNickname;
    private String authorNickname;

    public static ContentReportEntity create(ReviewTargetTypeVO targetType, String targetId, String authorId,
                                             String reporterId, String reason, String contentSnapshot) {
        ContentReportEntity reportEntity = new ContentReportEntity();
        reportEntity.targetType = targetType;
        reportEntity.targetId = targetId;
        reportEntity.authorId = authorId;
        reportEntity.reporterId = reporterId;
        reportEntity.reason = reason;
        reportEntity.contentSnapshot = contentSnapshot;
        reportEntity.status = ReportStatusVO.PENDING;
        reportEntity.createdAt = LocalDateTime.now();
        return reportEntity;
    }
}
