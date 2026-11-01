package com.betterschool.marketing.trigger.points.rpc;

import com.betterschool.marketing.api.points.IPointsExchangeService;
import com.betterschool.marketing.api.points.dto.PointsDeductRequestDTO;
import com.betterschool.marketing.api.points.dto.PointsDeductResponseDTO;
import com.betterschool.marketing.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.marketing.domain.points.service.IPointsAccountService;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.points.PointsReasonType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 兑换边 Provider（同步 Dubbo）：抽奖服务扣积分时调。
 * <p>
 * 业务失败（积分不足 / 参数不合法）返回结果对象而不是抛异常——异常跨 Dubbo 会把语义抹平，
 * 而抽奖侧要拿它决定返回前端的业务码。真正的系统故障（DB 不可用等）才让它抛出去，交调用方重试。
 */
@Slf4j
@DubboService
@RequiredArgsConstructor
public class PointsExchangeDubboService implements IPointsExchangeService {

    private final IPointsAccountService pointsAccountService;

    @Override
    public PointsDeductResponseDTO deduct(PointsDeductRequestDTO request) {
        if (request == null || request.getUserId() == null || request.getAmount() == null || request.getRefId() == null
                || request.getReasonType() == null) {
            return fail(ResponseCode.POINTS_PARAM_ERROR);
        }
        PointsReasonType reasonType;
        try {
            reasonType = PointsReasonType.valueOf(request.getReasonType());
        } catch (IllegalArgumentException e) {
            return fail(ResponseCode.POINTS_PARAM_ERROR);
        }
        try {
            PointsDeductResultVO result = pointsAccountService.deduct(request.getUserId(), request.getAmount(),
                    reasonType, request.getRefId());
            PointsDeductResponseDTO dto = new PointsDeductResponseDTO();
            dto.setSuccess(result.isSuccess());
            dto.setCode(result.getCode());
            dto.setInfo(result.getInfo());
            dto.setBalance(result.getBalance());
            return dto;
        } catch (AppException e) {
            // 参数类业务异常（额度非法 / 原因不支持）也走结果对象，不跨进程抛
            log.warn("[PointsExchange] 扣分入参不合法 userId={}, reasonType={}, refId={}, info={}",
                    request.getUserId(), request.getReasonType(), request.getRefId(), e.getInfo());
            PointsDeductResponseDTO dto = new PointsDeductResponseDTO();
            dto.setSuccess(false);
            dto.setCode(e.getCode());
            dto.setInfo(e.getInfo());
            return dto;
        }
    }

    private static PointsDeductResponseDTO fail(ResponseCode responseCode) {
        PointsDeductResponseDTO dto = new PointsDeductResponseDTO();
        dto.setSuccess(false);
        dto.setCode(responseCode.getCode());
        dto.setInfo(responseCode.getInfo());
        return dto;
    }
}
