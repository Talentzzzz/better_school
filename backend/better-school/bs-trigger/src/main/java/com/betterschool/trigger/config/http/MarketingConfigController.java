package com.betterschool.trigger.config.http;

import com.betterschool.api.config.IMarketingConfigService;
import com.betterschool.api.config.dto.DrawCountValidityResponseDTO;
import com.betterschool.api.config.dto.PointsValidityResponseDTO;
import com.betterschool.api.config.dto.RebateConfigResponseDTO;
import com.betterschool.api.config.dto.RebateConfigUpsertRequestDTO;
import com.betterschool.api.response.Response;
import com.betterschool.domain.draw.service.drawcount.IDrawCountAccountService;
import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.valobj.RebateTypeEnum;
import com.betterschool.domain.rebate.service.IRebateService;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
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
 * <p>
 * 一个后台面、两个域各管自己那份：积分有效期归 points 域、次数有效期归 draw 域（抽奖次数与抽奖同域）、
 * 行为返利规则归 rebate 域——没有「配置域」这一层。每个端点只碰一个域，不构成跨域编排，
 * 因此直接调域服务、不经 case。
 * <p>
 * 读需登录；写（PUT/POST）另有 ADMIN 校验（控制器内 role 判断，照 lottery 的 openBucket）。
 */
@RestController
@RequestMapping("/api/marketing/config")
@RequiredArgsConstructor
public class MarketingConfigController implements IMarketingConfigService {

    private final IPointsAccountService pointsAccountService;
    private final IDrawCountAccountService drawCountAccountService;
    private final IRebateService rebateService;

    @Override
    @GetMapping("/points-validity")
    public Response<PointsValidityResponseDTO> getPointsValidity() {
        return Response.ok(toPointsValidityDTO(pointsAccountService.getValidDays()));
    }

    @Override
    @PutMapping("/points-validity")
    public Response<PointsValidityResponseDTO> updatePointsValidity(@RequestParam int pointsValidDays) {
        requireAdmin();
        pointsAccountService.updateValidDays(pointsValidDays);
        return Response.ok(toPointsValidityDTO(pointsAccountService.getValidDays()));
    }

    @Override
    @GetMapping("/draw-count-validity")
    public Response<DrawCountValidityResponseDTO> getDrawCountValidity() {
        return Response.ok(toDrawCountValidityDTO(drawCountAccountService.getValidDays()));
    }

    @Override
    @PutMapping("/draw-count-validity")
    public Response<DrawCountValidityResponseDTO> updateDrawCountValidity(@RequestParam int drawCountValidDays) {
        requireAdmin();
        drawCountAccountService.updateValidDays(drawCountValidDays);
        return Response.ok(toDrawCountValidityDTO(drawCountAccountService.getValidDays()));
    }

    @Override
    @GetMapping("/rebates")
    public Response<List<RebateConfigResponseDTO>> listRebates() {
        return Response.ok(rebateService.listRebates().stream().map(this::toResponseDTO).toList());
    }

    @Override
    @PostMapping("/rebates")
    public Response<RebateConfigResponseDTO> upsertRebate(@RequestBody RebateConfigUpsertRequestDTO rebateConfigUpsertRequestDTO) {
        requireAdmin();
        if (rebateConfigUpsertRequestDTO == null) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR);
        }
        RebateConfigEntity rebateConfigEntity = new RebateConfigEntity();
        rebateConfigEntity.setActionType(rebateConfigUpsertRequestDTO.getActionType());
        rebateConfigEntity.setRebateType(parseRebateType(rebateConfigUpsertRequestDTO.getRebateType()));
        rebateConfigEntity.setRebateName(rebateConfigUpsertRequestDTO.getRebateName());
        rebateConfigEntity.setRebateDesc(rebateConfigUpsertRequestDTO.getRebateDesc());
        rebateConfigEntity.setRebateValue(rebateConfigUpsertRequestDTO.getRebateValue());
        rebateConfigEntity.setConfigId(rebateConfigUpsertRequestDTO.getConfigId());
        rebateConfigEntity.setState(rebateConfigUpsertRequestDTO.getState());
        return Response.ok(toResponseDTO(rebateService.upsertRebateConfig(rebateConfigEntity)));
    }

    private static void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new AppException(ResponseCodeEnum.FORBIDDEN, "无权限，仅管理员可改营销配置");
        }
    }

    /** DTO 字符串 → 枚举：转换失败按参数错误处理（与域服务的校验共同兜住非法取值） */
    private static RebateTypeEnum parseRebateType(String rebateType) {
        try {
            return RebateTypeEnum.valueOf(rebateType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR, "不支持的返利类型");
        }
    }

    private static PointsValidityResponseDTO toPointsValidityDTO(int pointsValidDays) {
        PointsValidityResponseDTO pointsValidityResponseDTO = new PointsValidityResponseDTO();
        pointsValidityResponseDTO.setPointsValidDays(pointsValidDays);
        return pointsValidityResponseDTO;
    }

    private static DrawCountValidityResponseDTO toDrawCountValidityDTO(int drawCountValidDays) {
        DrawCountValidityResponseDTO drawCountValidityResponseDTO = new DrawCountValidityResponseDTO();
        drawCountValidityResponseDTO.setDrawCountValidDays(drawCountValidDays);
        return drawCountValidityResponseDTO;
    }

    private RebateConfigResponseDTO toResponseDTO(RebateConfigEntity rebateConfigEntity) {
        RebateConfigResponseDTO rebateConfigResponseDTO = new RebateConfigResponseDTO();
        rebateConfigResponseDTO.setId(rebateConfigEntity.getId());
        rebateConfigResponseDTO.setActionType(rebateConfigEntity.getActionType());
        rebateConfigResponseDTO.setRebateType(rebateConfigEntity.getRebateType().name());
        rebateConfigResponseDTO.setRebateName(rebateConfigEntity.getRebateName());
        rebateConfigResponseDTO.setRebateDesc(rebateConfigEntity.getRebateDesc());
        rebateConfigResponseDTO.setRebateValue(rebateConfigEntity.getRebateValue());
        rebateConfigResponseDTO.setConfigId(rebateConfigEntity.getConfigId());
        rebateConfigResponseDTO.setState(rebateConfigEntity.getState());
        rebateConfigResponseDTO.setCreateTime(rebateConfigEntity.getCreateTime());
        rebateConfigResponseDTO.setUpdateTime(rebateConfigEntity.getUpdateTime());
        return rebateConfigResponseDTO;
    }
}
