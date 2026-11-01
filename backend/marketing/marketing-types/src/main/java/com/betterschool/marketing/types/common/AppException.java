package com.betterschool.marketing.types.common;

import lombok.Getter;

/**
 * 积分服务业务异常：业务规则不满足时抛出，由 GlobalExceptionHandler 转成统一 Response 信封。
 * code 取 4xxx 业务码，info 是要直接展示给用户的中文文案。
 */
@Getter
public class AppException extends RuntimeException {

    private final String code;
    private final String info;

    public AppException(String code, String info) {
        super(info);
        this.code = code;
        this.info = info;
    }

    public AppException(ResponseCode responseCode) {
        this(responseCode.getCode(), responseCode.getInfo());
    }

    public AppException(ResponseCode responseCode, String info) {
        this(responseCode.getCode(), info);
    }
}
