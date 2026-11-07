package com.betterschool.loadtest.inventory.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只有参数类错误转 400；其它异常一律 500 —— JMeter 的「错误率」必须只统计技术故障，
 * 业务卖完（SOLD_OUT）走的是 200。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "BAD_REQUEST");
        out.put("message", e.getMessage());
        return out;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> serverError(Exception e) {
        log.error("请求处理失败", e);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "INTERNAL_ERROR");
        out.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
        return out;
    }
}
