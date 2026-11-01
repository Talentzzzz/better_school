package com.betterschool.domain.moderation.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 举报单状态：PENDING 待处理 → PROCESSED(核实违规，已下架/扣分) / IGNORED(不实忽略)
 */
@Getter
@AllArgsConstructor
public enum ReportStatusVO {

    PENDING("待处理"),
    PROCESSED("已核实"),
    IGNORED("已忽略");

    private final String desc;

    public static ReportStatusVO from(String name) {
        try {
            return ReportStatusVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "举报状态不合法");
        }
    }
}
