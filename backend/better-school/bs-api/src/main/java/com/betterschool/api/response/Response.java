package com.betterschool.api.response;

import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.Data;

/**
 * 统一响应体
 */
@Data
public class Response<T> {

    private String code;
    private String info;
    private T data;

    public static <T> Response<T> ok(T data) {
        Response<T> response = new Response<>();
        response.setCode(ResponseCodeEnum.SUCCESS.getCode());
        response.setInfo(ResponseCodeEnum.SUCCESS.getInfo());
        response.setData(data);
        return response;
    }

    public static <T> Response<T> fail(String code, String info) {
        Response<T> response = new Response<>();
        response.setCode(code);
        response.setInfo(info);
        return response;
    }

    public static <T> Response<T> fail(BusinessException e) {
        return fail(e.getCode(), e.getMessage());
    }

    /** 营销域业务异常（合并前 marketing 侧 Response.fail(AppException)，随域归并） */
    public static <T> Response<T> fail(AppException e) {
        return fail(e.getCode(), e.getInfo());
    }
}
