package com.betterschool.domain.user.model.valobj;

/**
 * 用户状态
 */
public enum UserStatusEnum {

    NORMAL("正常"),
    BANNED("封禁");

    private final String desc;

    UserStatusEnum(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
