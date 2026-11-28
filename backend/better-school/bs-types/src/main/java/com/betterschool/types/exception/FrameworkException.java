package com.betterschool.types.exception;

import com.betterschool.types.enums.ResponseCodeEnum;
import lombok.Getter;

/**
 * 框架/基础设施异常：调用中间件、外部依赖失败时抛出
 */
@Getter
public class FrameworkException extends RuntimeException {

    private final String code;

    public FrameworkException(String message) {
        super(message);
        this.code = ResponseCodeEnum.SYSTEM_ERROR.getCode();
    }

    public FrameworkException(String message, Throwable cause) {
        super(message, cause);
        this.code = ResponseCodeEnum.SYSTEM_ERROR.getCode();
    }
}
