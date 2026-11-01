package com.betterschool.api.dto;

import lombok.Data;

/** 主动放回请求（审核员审不了退池）：reason 可选，仅记录不落库 */
@Data
public class ReviewTaskReleaseRequestDTO {

    /** 放回原因（可空；审不了就退池） */
    private String reason;
}
