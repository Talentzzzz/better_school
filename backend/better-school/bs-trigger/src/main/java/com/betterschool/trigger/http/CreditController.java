package com.betterschool.trigger.http;

import com.betterschool.api.ICreditService;
import com.betterschool.api.dto.AdminAdjustRequestDTO;
import com.betterschool.api.dto.AdminBanRequestDTO;
import com.betterschool.api.dto.AdminUnbanRequestDTO;
import com.betterschool.api.dto.CreditInfoResponseDTO;
import com.betterschool.api.dto.CreditLogResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.usecase.credit.CreditCase;
import com.betterschool.domain.credit.model.entity.CreditDetailEntity;
import com.betterschool.domain.credit.model.valobj.CreditReasonTypeEnum;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 信用分 HTTP 入口：my 系列需登录，admin 系列仅 ADMIN（controller 内角色校验）
 */
@RestController
@RequestMapping("/api/credit")
@RequiredArgsConstructor
public class CreditController implements ICreditService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CreditCase creditCase;

    @Override
    @GetMapping("/my")
    public Response<CreditInfoResponseDTO> myCreditInfo() {
        String userId = SecurityUtils.currentUserId();
        CreditInfoResponseDTO creditInfoResponseDTO = new CreditInfoResponseDTO();
        creditInfoResponseDTO.setCreditScore(creditCase.getCreditScore(userId));
        creditInfoResponseDTO.setStatus(creditCase.getStatus(userId));
        return Response.ok(creditInfoResponseDTO);
    }

    @Override
    @GetMapping("/my/logs")
    public Response<PageResult<CreditLogResponseDTO>> myLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        return Response.ok(pageLogs(userId, page, pageSize));
    }

    @Override
    @PostMapping("/admin/adjust")
    public Response<Void> adminAdjust(@Valid @RequestBody AdminAdjustRequestDTO adminAdjustRequestDTO) {
        requireAdmin();
        creditCase.adminAdjust(adminAdjustRequestDTO.getTargetUserId(), adminAdjustRequestDTO.getDelta(), adminAdjustRequestDTO.getReason());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/admin/ban")
    public Response<Void> adminBan(@Valid @RequestBody AdminBanRequestDTO adminBanRequestDTO) {
        requireAdmin();
        creditCase.adminBan(adminBanRequestDTO.getTargetUserId(), adminBanRequestDTO.getReason());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/admin/unban")
    public Response<Void> adminUnban(@Valid @RequestBody AdminUnbanRequestDTO adminUnbanRequestDTO) {
        requireAdmin();
        creditCase.adminUnban(adminUnbanRequestDTO.getTargetUserId());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/admin/logs")
    public Response<PageResult<CreditLogResponseDTO>> adminLogs(
            @RequestParam String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireAdmin();
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        return Response.ok(pageLogs(userId, page, pageSize));
    }

    private PageResult<CreditLogResponseDTO> pageLogs(String userId, int page, int pageSize) {
        long total = creditCase.countDetails(userId);
        List<CreditDetailEntity> logList = creditCase.listDetails(userId, page, pageSize);
        List<CreditLogResponseDTO> dtoList = logList.stream().map(this::toLogResponseDTO).toList();
        return new PageResult<>(page, pageSize, total, dtoList);
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "仅管理员可操作");
        }
    }

    private CreditLogResponseDTO toLogResponseDTO(CreditDetailEntity creditDetailEntity) {
        CreditLogResponseDTO creditLogResponseDTO = new CreditLogResponseDTO();
        creditLogResponseDTO.setId(creditDetailEntity.getId());
        creditLogResponseDTO.setChange(creditDetailEntity.getChange());
        creditLogResponseDTO.setReasonType(creditDetailEntity.getReasonType());
        CreditReasonTypeEnum creditReasonTypeVO = CreditReasonTypeEnum.from(creditDetailEntity.getReasonType());
        creditLogResponseDTO.setReasonDesc(creditReasonTypeVO == null ? creditDetailEntity.getReasonType() : creditReasonTypeVO.getDesc());
        creditLogResponseDTO.setRefId(creditDetailEntity.getRefId());
        creditLogResponseDTO.setCreatedAt(creditDetailEntity.getCreateTime());
        return creditLogResponseDTO;
    }
}
