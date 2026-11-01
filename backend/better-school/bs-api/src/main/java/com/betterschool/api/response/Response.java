package com.betterschool.api.response;

import com.betterschool.types.enums.ResponseCode;
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
        response.setCode(ResponseCode.SUCCESS.getCode());
        response.setInfo(ResponseCode.SUCCESS.getInfo());
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
}
