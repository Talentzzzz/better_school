package com.betterschool.api.dto;

import lombok.Data;

/** 领单请求（审核员拉一条工单）：targetType 可选——缺省 = 跨类型全局 FIFO */
@Data
public class ReviewTaskClaimRequestDTO {

    /** 内容类型过滤 POST/COMMENT/PRODUCT/ERRAND（只作过滤器，不做优先级插队；可空） */
    private String targetType;
}
