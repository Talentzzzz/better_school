package com.betterschool.trigger.http;

import com.betterschool.api.IAdminReviewService;
import com.betterschool.api.dto.ReviewDecideRequestDTO;
import com.betterschool.api.response.Response;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.usecase.moderation.ReviewCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一裁决 HTTP 入口：人工 = 管理员登录态。operatorId 落 content_review 审核记录
 */
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
public class AdminReviewController implements IAdminReviewService {

    private final ReviewCase reviewCase;

    @Override
    @PostMapping("/decide")
    public Response<Void> decide(@Valid @RequestBody ReviewDecideRequestDTO request) {
        requireAdmin();
        reviewCase.decide(request.getTargetType(), request.getTargetId(), Boolean.TRUE.equals(request.getApprove()),
                request.getReasonCode(), request.getSupplement(), SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "仅管理员可操作");
        }
    }
}
