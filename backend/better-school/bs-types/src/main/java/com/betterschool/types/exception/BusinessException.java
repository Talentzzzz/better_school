package com.betterschool.types.exception;

import com.betterschool.types.enums.ResponseCode;
import lombok.Getter;

/**
 * 业务异常：业务规则不满足时抛出，由全局异常处理器转为统一响应
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String message) {
        super(message);
        this.code = ResponseCode.BUSINESS_ERROR.getCode();
    }

    public BusinessException(ResponseCode responseCode) {
        super(responseCode.getInfo());
        this.code = responseCode.getCode();
    }

    public BusinessException(ResponseCode responseCode, String message) {
        super(message);
        this.code = responseCode.getCode();
    }
}
