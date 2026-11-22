package com.betterschool.trigger.http;

import com.betterschool.api.response.Response;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.exception.FrameworkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Response<Void> handleBusinessException(BusinessException e) {
        return Response.fail(e.getCode(), e.getMessage());
    }

    /** 营销域业务异常（合并前 marketing trigger 侧 GlobalExceptionHandler 的 AppException 分支，归并进本类） */
    @ExceptionHandler(AppException.class)
    public Response<Void> handleAppException(AppException e) {
        return Response.fail(e.getCode(), e.getInfo());
    }

    /** multipart 容器上限（6MB）超限在进 Controller 前抛出，返回业务提示而非系统错误 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Response<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return Response.fail(ResponseCode.PARAM_ERROR.getCode(), "单张图片不能超过 5MB");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String info = fieldError == null ? ResponseCode.PARAM_ERROR.getInfo() : fieldError.getDefaultMessage();
        return Response.fail(ResponseCode.PARAM_ERROR.getCode(), info);
    }

    @ExceptionHandler(FrameworkException.class)
    public Response<Void> handleFrameworkException(FrameworkException e) {
        log.error("framework error", e);
        return Response.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Response<Void> handleException(Exception e) {
        log.error("system error", e);
        return Response.fail(ResponseCode.SYSTEM_ERROR.getCode(), ResponseCode.SYSTEM_ERROR.getInfo());
    }
}
