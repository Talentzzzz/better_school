package com.betterschool.domain.moderation.service;

import com.betterschool.domain.moderation.adapter.repository.IModerationRepository;
import com.betterschool.domain.moderation.model.entity.ContentReportEntity;
import com.betterschool.domain.moderation.model.valobj.ReportProcessResult;
import com.betterschool.domain.moderation.model.valobj.ReportStatusVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements IReportService {

    private final IModerationRepository moderationRepository;

    @Override
    public String submitReport(String reporterId, ReviewTargetTypeVO targetType, String targetId, String authorId,
                               String reason, String contentSnapshot) {
        return moderationRepository.insertIgnoreReport(
                ContentReportEntity.create(targetType, targetId, authorId, reporterId, reason, contentSnapshot));
    }

    @Override
    public ReportProcessResult processReport(String reportId, ReportStatusVO targetStatus, String handlerId) {
        ContentReportEntity reportEntity = moderationRepository.findReportById(reportId);
        if (reportEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "举报单不存在");
        }
        if (reportEntity.getTargetType() == null) {
            // 历史脏数据/未知 target_type，无法编排处理
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "举报单数据异常，无法处理");
        }
        boolean changed = ReportStatusVO.PROCESSED == targetStatus
                ? moderationRepository.processById(reportId, handlerId)
                : moderationRepository.ignoreById(reportId, handlerId);
        if (!changed && reportEntity.getStatus() != targetStatus) {
            // 0 行且非目标态 = 已被处理成其他终态（如 IGNORED 后再核实），状态冲突
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "举报单状态冲突，已被处理");
        }
        // 幂等重试也返回准确需求：needDeduct 依据流水判定（同 target 已有 REPORT_CONFIRMED 流水则不罚，一内容一罚）
        boolean needDeduct = !moderationRepository.existsProcessedCreditLog(
                reportEntity.getAuthorId(), reportEntity.getTargetType().name(), reportEntity.getTargetId());
        return new ReportProcessResult(targetStatus, reportEntity.getTargetType(), reportEntity.getTargetId(),
                reportEntity.getAuthorId(), reportEntity.getReporterId(), needDeduct);
    }

    @Override
    public List<ContentReportEntity> listReports(ReportStatusVO status, int page, int pageSize) {
        return moderationRepository.pageReports(status == null ? null : status.name(), (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countReports(ReportStatusVO status) {
        return moderationRepository.countReports(status == null ? null : status.name());
    }
}
