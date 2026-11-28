package com.betterschool.trigger.points.rpc;

import com.betterschool.api.points.IPointsExchangeService;
import com.betterschool.api.points.dto.PointsDeductRequestDTO;
import com.betterschool.api.points.dto.PointsDeductResponseDTO;
import com.betterschool.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.points.PointsReasonTypeEnum;
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
    public PointsDeductResponseDTO deduct(PointsDeductRequestDTO pointsDeductRequestDTO) {
        if (pointsDeductRequestDTO == null || pointsDeductRequestDTO.getUserId() == null || pointsDeductRequestDTO.getAmount() == null || pointsDeductRequestDTO.getRefId() == null
                || pointsDeductRequestDTO.getReasonType() == null) {
            return fail(ResponseCodeEnum.POINTS_PARAM_ERROR);
        }
        PointsReasonTypeEnum pointsReasonType;
        try {
            pointsReasonType = PointsReasonTypeEnum.valueOf(pointsDeductRequestDTO.getReasonType());
        } catch (IllegalArgumentException e) {
            return fail(ResponseCodeEnum.POINTS_PARAM_ERROR);
        }
        try {
            PointsDeductResultVO pointsDeductResultVO = pointsAccountService.deduct(pointsDeductRequestDTO.getUserId(), pointsDeductRequestDTO.getAmount(),
                    pointsReasonType, pointsDeductRequestDTO.getRefId());
            PointsDeductResponseDTO pointsDeductResponseDTO = new PointsDeductResponseDTO();
            pointsDeductResponseDTO.setSuccess(pointsDeductResultVO.isSuccess());
            pointsDeductResponseDTO.setCode(pointsDeductResultVO.getCode());
            pointsDeductResponseDTO.setInfo(pointsDeductResultVO.getInfo());
            pointsDeductResponseDTO.setBalance(pointsDeductResultVO.getBalance());
            return pointsDeductResponseDTO;
        } catch (AppException e) {
            // 参数类业务异常（额度非法 / 原因不支持）也走结果对象，不跨进程抛
            log.warn("[PointsExchange] 扣分入参不合法 userId={}, reasonType={}, refId={}, info={}",
                    pointsDeductRequestDTO.getUserId(), pointsDeductRequestDTO.getReasonType(), pointsDeductRequestDTO.getRefId(), e.getInfo());
            PointsDeductResponseDTO pointsDeductResponseDTO = new PointsDeductResponseDTO();
            pointsDeductResponseDTO.setSuccess(false);
            pointsDeductResponseDTO.setCode(e.getCode());
            pointsDeductResponseDTO.setInfo(e.getInfo());
            return pointsDeductResponseDTO;
        }
    }

    private static PointsDeductResponseDTO fail(ResponseCodeEnum responseCode) {
        PointsDeductResponseDTO pointsDeductResponseDTO = new PointsDeductResponseDTO();
        pointsDeductResponseDTO.setSuccess(false);
        pointsDeductResponseDTO.setCode(responseCode.getCode());
        pointsDeductResponseDTO.setInfo(responseCode.getInfo());
        return pointsDeductResponseDTO;
    }
}
