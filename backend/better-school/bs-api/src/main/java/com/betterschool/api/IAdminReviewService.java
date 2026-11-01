package com.betterschool.api;

import com.betterschool.api.dto.ReviewDecideRequestDTO;
import com.betterschool.api.response.Response;

/**
 * 统一裁决契约：人工（管理员登录态）统一裁决入口。
 * 既有审核员接口（POST /api/moderation/review，AUDITOR/ADMIN）行为不变，内部转发同一用例
 */
public interface IAdminReviewService {

    /** 统一裁决：通过 → 对外可见；拒绝 → 打回（跑腿订单解冻退款），reason 必填 */
    Response<Void> decide(ReviewDecideRequestDTO request);
}
