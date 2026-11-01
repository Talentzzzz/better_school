package com.betterschool.marketing.trigger.config.http;

import com.betterschool.marketing.api.config.IMarketingConfigService;
import com.betterschool.marketing.api.config.dto.DrawCountValidityResponseDTO;
import com.betterschool.marketing.api.config.dto.PointsValidityResponseDTO;
import com.betterschool.marketing.api.config.dto.RebateConfigResponseDTO;
import com.betterschool.marketing.api.config.dto.RebateConfigUpsertRequestDTO;
import com.betterschool.marketing.api.common.response.Response;
import com.betterschool.marketing.domain.config.model.entity.RebateConfigEntity;
import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;
import com.betterschool.marketing.domain.config.service.IConfigService;
import com.betterschool.marketing.trigger.common.http.security.SecurityUtils;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 营销配置后台 HTTP 入口（桶模型配套，票 10）：有效期配置、次数配置、行为返利配置的读写。
 * 读需登录；写（PUT/POST）另有 ADMIN 校验（控制器内 role 判断，照 lottery 的 openBucket）。
 */
@RestController
@RequestMapping("/api/marketing/config")
@RequiredArgsConstructor
public class MarketingConfigController implements IMarketingConfigService {

    private final IConfigService configService;

    @Override
    @GetMapping("/points-validity")
    public Response<PointsValidityResponseDTO> getPointsValidity() {
        return Response.ok(toPointsValidityDTO(configService.getPointsValidDays()));
    }

    @Override
    @PutMapping("/points-validity")
    public Response<PointsValidityResponseDTO> updatePointsValidity(@RequestParam int pointsValidDays) {
        requireAdmin();
        configService.updatePointsValidDays(pointsValidDays);
        return Response.ok(toPointsValidityDTO(configService.getPointsValidDays()));
    }

    @Override
    @GetMapping("/draw-count-validity")
    public Response<DrawCountValidityResponseDTO> getDrawCountValidity() {
        return Response.ok(toDrawCountValidityDTO(configService.getDrawCountValidDays()));
    }

    @Override
    @PutMapping("/draw-count-validity")
    public Response<DrawCountValidityResponseDTO> updateDrawCountValidity(@RequestParam int drawCountValidDays) {
        requireAdmin();
        configService.updateDrawCountValidDays(drawCountValidDays);
        return Response.ok(toDrawCountValidityDTO(configService.getDrawCountValidDays()));
    }

    @Override
    @GetMapping("/rebates")
    public Response<List<RebateConfigResponseDTO>> listRebates() {
        return Response.ok(configService.listRebates().stream().map(this::toResponseDTO).toList());
    }

    @Override
    @PostMapping("/rebates")
    public Response<RebateConfigResponseDTO> upsertRebate(@RequestBody RebateConfigUpsertRequestDTO request) {
        requireAdmin();
        if (request == null) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR);
        }
        RebateConfigEntity rebate = new RebateConfigEntity();
        rebate.setActionType(request.getActionType());
        rebate.setRebateType(parseRebateType(request.getRebateType()));
        rebate.setRebateName(request.getRebateName());
        rebate.setRebateDesc(request.getRebateDesc());
        rebate.setRebateValue(request.getRebateValue());
        rebate.setConfigId(request.getConfigId());
        rebate.setState(request.getState());
        return Response.ok(toResponseDTO(configService.upsertRebate(rebate)));
    }

    private static void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new AppException(ResponseCode.FORBIDDEN, "无权限，仅管理员可改营销配置");
        }
    }

    /** DTO 字符串 → 枚举：转换失败按参数错误处理（与域服务的校验共同兜住非法取值） */
    private static RebateTypeVO parseRebateType(String rebateType) {
        try {
            return RebateTypeVO.valueOf(rebateType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AppException(ResponseCode.CONFIG_PARAM_ERROR, "不支持的返利类型");
        }
    }

    private static PointsValidityResponseDTO toPointsValidityDTO(int pointsValidDays) {
        PointsValidityResponseDTO dto = new PointsValidityResponseDTO();
        dto.setPointsValidDays(pointsValidDays);
        return dto;
    }

    private static DrawCountValidityResponseDTO toDrawCountValidityDTO(int drawCountValidDays) {
        DrawCountValidityResponseDTO dto = new DrawCountValidityResponseDTO();
        dto.setDrawCountValidDays(drawCountValidDays);
        return dto;
    }

    private RebateConfigResponseDTO toResponseDTO(RebateConfigEntity rebate) {
        RebateConfigResponseDTO dto = new RebateConfigResponseDTO();
        dto.setId(rebate.getId());
        dto.setActionType(rebate.getActionType());
        dto.setRebateType(rebate.getRebateType().name());
        dto.setRebateName(rebate.getRebateName());
        dto.setRebateDesc(rebate.getRebateDesc());
        dto.setRebateValue(rebate.getRebateValue());
        dto.setConfigId(rebate.getConfigId());
        dto.setState(rebate.getState());
        dto.setCreateTime(rebate.getCreateTime());
        dto.setUpdateTime(rebate.getUpdateTime());
        return dto;
    }
}
