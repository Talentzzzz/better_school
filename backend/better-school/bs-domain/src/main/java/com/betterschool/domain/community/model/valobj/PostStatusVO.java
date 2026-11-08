package com.betterschool.domain.community.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PostStatusVO {

    NORMAL("正常"),
    DRAFT("草稿"),
    DELETED("已删除");

    private final String desc;
}
