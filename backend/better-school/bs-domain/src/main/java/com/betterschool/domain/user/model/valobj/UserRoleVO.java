package com.betterschool.domain.user.model.valobj;

/**
 * 用户角色
 */
public enum UserRoleVO {

    USER("普通用户"),
    ADMIN("超级管理员");

    private final String desc;

    UserRoleVO(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
