package com.betterschool.marketing.api.config;

import com.betterschool.marketing.api.common.response.Response;
import com.betterschool.marketing.api.config.dto.DrawCountValidityResponseDTO;
import com.betterschool.marketing.api.config.dto.PointsValidityResponseDTO;
import com.betterschool.marketing.api.config.dto.RebateConfigResponseDTO;
import com.betterschool.marketing.api.config.dto.RebateConfigUpsertRequestDTO;

import java.util.List;

/**
 * 营销配置后台契约（桶模型配套，票 10）：积分/次数有效期 + 行为返利规则。
 * <p>
 * 读接口需登录、写接口另有 ADMIN 校验（控制器内 role 判断，照 {@code openBucket}）。
 * 与用户面的 {@code IPointsService}（只读、零写）互不相干——本接口是后台管理面，
 * 用户面契约不变。
 */
public interface IMarketingConfigService {

    /** 积分有效期天数（points_validity_config 单行） */
    Response<PointsValidityResponseDTO> getPointsValidity();

    /** 更新积分有效期天数（ADMIN）；天数必须为正 */
    Response<PointsValidityResponseDTO> updatePointsValidity(int pointsValidDays);

    /** 抽奖次数有效期天数（draw_count_config 单行） */
    Response<DrawCountValidityResponseDTO> getDrawCountValidity();

    /** 更新抽奖次数有效期天数（ADMIN）；天数必须为正 */
    Response<DrawCountValidityResponseDTO> updateDrawCountValidity(int drawCountValidDays);

    /** 全部行为返利规则（后台列表） */
    Response<List<RebateConfigResponseDTO>> listRebates();

    /** 按 (actionType, rebateType) upsert 一条返利规则（ADMIN） */
    Response<RebateConfigResponseDTO> upsertRebate(RebateConfigUpsertRequestDTO request);
}
