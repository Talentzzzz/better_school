package com.betterschool.marketing.trigger.common.http;

import com.betterschool.marketing.api.common.response.Response;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：业务失败走 4xxx 业务码 + 中文文案，其余一律 999（不外泄堆栈）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public Response<Void> handleAppException(AppException e) {
        return Response.fail(e.getCode(), e.getInfo());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String info = fieldError == null ? ResponseCode.POINTS_PARAM_ERROR.getInfo() : fieldError.getDefaultMessage();
        return Response.fail(ResponseCode.POINTS_PARAM_ERROR.getCode(), info);
    }

    @ExceptionHandler(Exception.class)
    public Response<Void> handleException(Exception e) {
        log.error("system error", e);
        return Response.fail(ResponseCode.SYSTEM_ERROR.getCode(), ResponseCode.SYSTEM_ERROR.getInfo());
    }
}
