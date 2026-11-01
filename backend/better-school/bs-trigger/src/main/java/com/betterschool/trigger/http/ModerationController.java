package com.betterschool.trigger.http;

import com.betterschool.api.IModerationService;
import com.betterschool.api.dto.AssignAuditorRequestDTO;
import com.betterschool.api.dto.ReportProcessRequestDTO;
import com.betterschool.api.dto.ReportResponseDTO;
import com.betterschool.api.dto.ReportSubmitRequestDTO;
import com.betterschool.api.dto.ReviewQueueItemResponseDTO;
import com.betterschool.api.dto.ReviewHistoryItemResponseDTO;
import com.betterschool.api.dto.ReviewRequestDTO;
import com.betterschool.api.dto.ReviewTaskClaimRequestDTO;
import com.betterschool.api.dto.ReviewTaskClaimResponseDTO;
import com.betterschool.api.dto.ReviewTaskDecideRequestDTO;
import com.betterschool.api.dto.ReviewTaskDetailResponseDTO;
import com.betterschool.api.dto.ReviewTaskReleaseRequestDTO;
import com.betterschool.api.dto.SensitiveWordRequestDTO;
import com.betterschool.api.dto.SensitiveWordResponseDTO;
import com.betterschool.api.dto.WsTicketResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.domain.moderation.model.entity.ContentReportEntity;
import com.betterschool.domain.moderation.model.entity.ContentReviewEntity;
import com.betterschool.domain.moderation.model.entity.ReviewQueueItemEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskDetailEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskEntity;
import com.betterschool.domain.moderation.model.entity.SensitiveWordEntity;
import com.betterschool.domain.moderation.model.valobj.ReportStatusVO;
import com.betterschool.domain.moderation.model.valobj.ReviewActionVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.domain.moderation.service.IModerationScreenService;
import com.betterschool.domain.moderation.service.IReportService;
import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.domain.chat.adapter.port.IWsTicketPort;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.usecase.moderation.ReportCase;
import com.betterschool.usecase.moderation.ReviewCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内容治理 HTTP 入口：举报需登录；审核队列/审核动作/举报处理需 AUDITOR 或 ADMIN；
 * 敏感词管理/审核员指派仅 ADMIN
 */
@RestController
@RequestMapping("/api/moderation")
@RequiredArgsConstructor
public class ModerationController implements IModerationService {

    private static final int MAX_PAGE_SIZE = 50;

    private final IReviewService reviewService;
    private final IReportService reportService;
    private final IModerationScreenService moderationScreenService;
    private final ReviewCase reviewCase;
    private final ReportCase reportCase;
    /** WS 连接票据签发（端点无关能力，chat/review 共用同一签发实现，票 10） */
    private final IWsTicketPort wsTicketPort;

    @Override
    @PostMapping("/ws-ticket")
    public Response<WsTicketResponseDTO> createWsTicket() {
        requireAuditor();
        WsTicketResponseDTO dto = new WsTicketResponseDTO();
        dto.setTicket(wsTicketPort.issue(SecurityUtils.currentUserId()));
        return Response.ok(dto);
    }

    @Override
    @PostMapping("/reports")
    public Response<Long> submitReport(@Valid @RequestBody ReportSubmitRequestDTO request) {
        Long reportId = reportCase.submitReport(SecurityUtils.currentUserId(), request.getTargetType(),
                request.getTargetId(), request.getReason());
        return Response.ok(reportId);
    }

    @Override
    @PostMapping("/reports/{reportId}/process")
    public Response<Void> processReport(@PathVariable Long reportId,
                                        @Valid @RequestBody ReportProcessRequestDTO request) {
        requireAuditor();
        reportCase.processReport(reportId, request.getAction(), SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/review")
    public Response<Void> review(@Valid @RequestBody ReviewRequestDTO request) {
        requireAuditor();
        reviewCase.review(request.getTargetType(), request.getTargetId(),
                ReviewActionVO.from(request.getAction()), SecurityUtils.currentUserId(),
                request.getReasonCode(), request.getSupplement());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/queue")
    public Response<PageResult<ReviewQueueItemResponseDTO>> listReviewQueue(
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireAuditor();
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        // 类型过滤可选：缺省 = 跨类型取候选（工单池 entered_at 全局 FIFO，spec §5.1 队列口径切换）
        ReviewTargetTypeVO targetTypeVO = targetType == null || targetType.isBlank()
                ? null : ReviewTargetTypeVO.from(targetType);
        if (targetTypeVO == null && targetType != null && !targetType.isBlank()) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "审核对象类型不合法");
        }
        long total = reviewService.countReviewQueue(targetTypeVO);
        List<ReviewQueueItemEntity> queueList = reviewService.listReviewQueue(targetTypeVO, page, pageSize);
        List<ReviewQueueItemResponseDTO> dtoList = queueList.stream().map(this::toQueueItemResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/tasks/counts")
    public Response<Map<String, Long>> reviewTaskCounts() {
        requireAuditor();
        return Response.ok(reviewService.backlogCounts());
    }

    @Override
    @PostMapping("/tasks/claim")
    public Response<ReviewTaskClaimResponseDTO> claimTask(
            @RequestBody(required = false) ReviewTaskClaimRequestDTO request) {
        requireAuditor();
        // 类型过滤可选：缺省 = 跨类型取候选（全局 FIFO；类型只作过滤器，spec §5.3）
        ReviewTargetTypeVO targetTypeVO = null;
        if (request != null && request.getTargetType() != null && !request.getTargetType().isBlank()) {
            targetTypeVO = ReviewTargetTypeVO.from(request.getTargetType());
            if (targetTypeVO == null) {
                throw new BusinessException(ResponseCode.PARAM_ERROR, "审核对象类型不合法");
            }
        }
        ReviewTaskEntity taskEntity = reviewService.claimTask(targetTypeVO, SecurityUtils.currentUserId());
        // 池空 / 并发抢占落空：data=null（前端转「暂无可领工单」，不报错）
        return Response.ok(taskEntity == null ? null : toClaimResponseDTO(taskEntity));
    }

    @Override
    @PostMapping("/tasks/{taskId}/release")
    public Response<Void> releaseTask(@PathVariable Long taskId,
                                      @RequestBody(required = false) ReviewTaskReleaseRequestDTO request) {
        requireAuditor();
        String reason = request == null ? null : request.getReason();
        reviewService.releaseTask(taskId, SecurityUtils.currentUserId(), reason);
        return Response.ok(null);
    }

    @Override
    @PostMapping("/tasks/{taskId}/decide")
    public Response<Void> decideTask(@PathVariable Long taskId,
                                     @Valid @RequestBody ReviewTaskDecideRequestDTO request) {
        requireAuditor();
        // 动作走既有枚举解析（非法动作 PARAM_ERROR），与遗留审核入口同口径
        ReviewActionVO action = ReviewActionVO.from(request.getAction());
        reviewCase.decideTask(taskId, action == ReviewActionVO.APPROVE,
                request.getReasonCode(), request.getSupplement(), SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/tasks/{taskId}")
    public Response<ReviewTaskDetailResponseDTO> reviewTaskDetail(@PathVariable Long taskId) {
        requireAuditor();
        return Response.ok(toTaskDetailResponseDTO(reviewService.reviewTaskDetail(taskId)));
    }

    @Override
    @GetMapping("/queue/{targetType}/{targetId}")
    public Response<ReviewQueueItemResponseDTO> reviewDetail(@PathVariable String targetType,
                                                             @PathVariable String targetId) {
        requireAuditor();
        ReviewTargetTypeVO targetTypeVO = ReviewTargetTypeVO.from(targetType);
        if (targetTypeVO == null) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "审核对象类型不合法");
        }
        return Response.ok(toQueueItemResponseDTO(reviewService.reviewDetail(targetTypeVO, targetId)));
    }

    @Override
    @GetMapping("/reports")
    public Response<PageResult<ReportResponseDTO>> listReports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireAuditor();
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        ReportStatusVO statusVO = status == null || status.isBlank() ? null : ReportStatusVO.from(status);
        long total = reportService.countReports(statusVO);
        List<ContentReportEntity> reportList = reportService.listReports(statusVO, page, pageSize);
        List<ReportResponseDTO> dtoList = reportList.stream().map(this::toReportResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/admin/words")
    public Response<PageResult<SensitiveWordResponseDTO>> listSensitiveWords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireAdmin();
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = moderationScreenService.countWords();
        List<SensitiveWordEntity> wordList = moderationScreenService.listWords(page, pageSize);
        List<SensitiveWordResponseDTO> dtoList = wordList.stream().map(this::toWordResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @PostMapping("/admin/words")
    public Response<Void> addSensitiveWord(@Valid @RequestBody SensitiveWordRequestDTO request) {
        requireAdmin();
        moderationScreenService.adminAddWord(request.getWord());
        return Response.ok(null);
    }

    @Override
    @DeleteMapping("/admin/words/{wordId}")
    public Response<Void> deleteSensitiveWord(@PathVariable Long wordId) {
        requireAdmin();
        moderationScreenService.adminDeleteWord(wordId);
        return Response.ok(null);
    }

    @Override
    @PostMapping("/admin/auditors")
    public Response<Void> assignAuditor(@Valid @RequestBody AssignAuditorRequestDTO request) {
        requireAdmin();
        reviewService.assignAuditor(request.getUserId());
        return Response.ok(null);
    }

    @Override
    @DeleteMapping("/admin/auditors")
    public Response<Void> removeAuditor(@Valid @RequestBody AssignAuditorRequestDTO request) {
        requireAdmin();
        reviewService.removeAuditor(request.getUserId());
        return Response.ok(null);
    }

    private void requireAuditor() {
        String role = SecurityUtils.currentRole();
        if (!"AUDITOR".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "仅审核员或管理员可操作");
        }
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "仅管理员可操作");
        }
    }

    private ReviewQueueItemResponseDTO toQueueItemResponseDTO(ReviewQueueItemEntity queueItemEntity) {
        ReviewQueueItemResponseDTO dto = new ReviewQueueItemResponseDTO();
        dto.setTargetType(queueItemEntity.getTargetType().name());
        dto.setTargetId(queueItemEntity.getTargetId());
        dto.setAuthorId(queueItemEntity.getAuthorId());
        dto.setAuthorNickname(queueItemEntity.getAuthorNickname());
        dto.setContent(queueItemEntity.getContent());
        dto.setReward(queueItemEntity.getReward());
        dto.setImages(queueItemEntity.getImages());
        dto.setStatus(queueItemEntity.getStatus());
        dto.setCreatedAt(queueItemEntity.getCreatedAt());
        dto.setEnteredAt(queueItemEntity.getEnteredAt());
        return dto;
    }

    /** 领单结果 DTO 装配：工单定位 + 租约/轮次派单态（内容素材前端再走 GET /tasks/{taskId}） */
    private ReviewTaskClaimResponseDTO toClaimResponseDTO(ReviewTaskEntity taskEntity) {
        ReviewTaskClaimResponseDTO dto = new ReviewTaskClaimResponseDTO();
        dto.setTaskId(taskEntity.getId());
        dto.setTargetType(taskEntity.getTargetType().name());
        dto.setTargetId(taskEntity.getTargetId());
        dto.setReviewRound(taskEntity.getReviewRound());
        dto.setClaimState(taskEntity.getClaimState());
        dto.setClaimSeq(taskEntity.getClaimSeq());
        dto.setLeaseExpireAt(taskEntity.getLeaseExpireAt());
        dto.setRecycleCount(taskEntity.getRecycleCount());
        dto.setEscalated(taskEntity.isEscalated());
        dto.setEnteredAt(taskEntity.getEnteredAt());
        return dto;
    }

    /** 工单详情聚合 DTO 装配：工单态 + 素材（通用列/类型专属上下文）+ 两路历史裁决 */
    private ReviewTaskDetailResponseDTO toTaskDetailResponseDTO(ReviewTaskDetailEntity detailEntity) {
        ReviewTaskDetailResponseDTO dto = new ReviewTaskDetailResponseDTO();
        dto.setTaskId(detailEntity.getTask().getId());
        dto.setTargetType(detailEntity.getTask().getTargetType().name());
        dto.setTargetId(detailEntity.getTask().getTargetId());
        dto.setReviewRound(detailEntity.getTask().getReviewRound());
        dto.setClaimState(detailEntity.getTask().getClaimState());
        dto.setEnteredAt(detailEntity.getTask().getEnteredAt());
        dto.setDecidedAt(detailEntity.getTask().getDecidedAt());
        dto.setAuthorId(detailEntity.getTarget().getAuthorId());
        dto.setAuthorNickname(detailEntity.getTarget().getAuthorNickname());
        dto.setAuthorAvatar(detailEntity.getTarget().getAuthorAvatar());
        dto.setContent(detailEntity.getTarget().getContent());
        dto.setImages(detailEntity.getTarget().getImages());
        dto.setReward(detailEntity.getTarget().getReward());
        dto.setStatus(detailEntity.getTarget().getStatus());
        dto.setContextPostId(detailEntity.getTarget().getContextPostId());
        dto.setContextPostTitle(detailEntity.getTarget().getContextPostTitle());
        dto.setContextParentId(detailEntity.getTarget().getContextParentId());
        dto.setContextParentContent(detailEntity.getTarget().getContextParentContent());
        dto.setTargetReviews(detailEntity.getTargetReviews().stream().map(this::toHistoryItemDTO).toList());
        dto.setAuthorReviews(detailEntity.getAuthorReviews().stream().map(this::toHistoryItemDTO).toList());
        return dto;
    }

    private ReviewHistoryItemResponseDTO toHistoryItemDTO(ContentReviewEntity reviewEntity) {
        ReviewHistoryItemResponseDTO dto = new ReviewHistoryItemResponseDTO();
        dto.setReviewId(reviewEntity.getId());
        dto.setTargetType(reviewEntity.getTargetType() == null ? null : reviewEntity.getTargetType().name());
        dto.setTargetId(reviewEntity.getTargetId());
        dto.setReviewRound(reviewEntity.getReviewRound());
        dto.setAction(reviewEntity.getAction() == null ? null : reviewEntity.getAction().name());
        dto.setReason(reviewEntity.getReason());
        dto.setReasonCode(reviewEntity.getReasonCode());
        dto.setOperatorId(reviewEntity.getOperatorId());
        dto.setCreatedAt(reviewEntity.getCreatedAt());
        return dto;
    }

    private ReportResponseDTO toReportResponseDTO(ContentReportEntity reportEntity) {
        ReportResponseDTO dto = new ReportResponseDTO();
        dto.setId(reportEntity.getId());
        dto.setTargetType(reportEntity.getTargetType().name());
        dto.setTargetId(reportEntity.getTargetId());
        dto.setAuthorId(reportEntity.getAuthorId());
        dto.setAuthorNickname(reportEntity.getAuthorNickname());
        dto.setReporterId(reportEntity.getReporterId());
        dto.setReporterNickname(reportEntity.getReporterNickname());
        dto.setReason(reportEntity.getReason());
        dto.setContentSnapshot(reportEntity.getContentSnapshot());
        dto.setStatus(reportEntity.getStatus().name());
        dto.setHandlerId(reportEntity.getHandlerId());
        dto.setHandledAt(reportEntity.getHandledAt());
        dto.setCreatedAt(reportEntity.getCreatedAt());
        return dto;
    }

    private SensitiveWordResponseDTO toWordResponseDTO(SensitiveWordEntity wordEntity) {
        SensitiveWordResponseDTO dto = new SensitiveWordResponseDTO();
        dto.setId(wordEntity.getId());
        dto.setWord(wordEntity.getWord());
        dto.setEnabled(wordEntity.isEnabled());
        dto.setCreatedAt(wordEntity.getCreatedAt());
        return dto;
    }
}
