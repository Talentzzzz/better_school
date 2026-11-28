package com.betterschool.domain.post.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PostStatusEnum {

    NORMAL("正常"),
    DRAFT("草稿"),
    DELETED("已删除");

    private final String desc;
}
