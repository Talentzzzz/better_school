package com.betterschool.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应码
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {

    SUCCESS("200", "成功"),
    PARAM_ERROR("400", "参数错误"),
    UNAUTHORIZED("401", "未登录或登录已过期"),
    FORBIDDEN("403", "无权限"),
    NOT_FOUND("404", "资源不存在"),
    BUSINESS_ERROR("500", "业务处理失败"),
    SYSTEM_ERROR("999", "系统繁忙，请稍后重试");

    private final String code;
    private final String info;
}
