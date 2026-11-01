package com.betterschool.marketing.api.common.response;

import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import lombok.Data;

/**
 * 统一响应体（前端 http.ts 的 unwrap 按 {code, info, data} 解包，三个服务各带一份自己的）。
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

    public static <T> Response<T> fail(AppException e) {
        return fail(e.getCode(), e.getInfo());
    }
}
