package com.betterschool.domain.moderation.service;

import com.betterschool.domain.moderation.model.entity.ContentReportEntity;
import com.betterschool.domain.moderation.model.valobj.ReportProcessResult;
import com.betterschool.domain.moderation.model.valobj.ReportStatusVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;

import java.util.List;

/**
 * moderation 域举报服务（命名避免与 api.IModerationService 契约重名）。
 * 域内只做：举报单写入/状态流转/列表查询；下架/扣分/通知由 case 层编排（不依赖其他域）
 */
public interface IReportService {

    /**
     * 提交举报（uk(reporter_id,target_type,target_id) 幂等）：返回举报单 id；同一人对同一内容重复举报返回 null
     */
    Long submitReport(String reporterId, ReviewTargetTypeVO targetType, String targetId, String authorId,
                      String reason, String contentSnapshot);

    /**
     * 审核员处理举报：条件 UPDATE PENDING→PROCESSED/IGNORED。
     * 幂等语义：重试（0 行）时仍返回准确需求（needDeduct 依据 user_credit_detail 流水判定），case 层照调 applyChange 靠 uk 兜底不丢扣分
     */
    ReportProcessResult processReport(Long reportId, ReportStatusVO targetStatus, String handlerId);

    List<ContentReportEntity> listReports(ReportStatusVO status, int page, int pageSize);

    long countReports(ReportStatusVO status);
}
