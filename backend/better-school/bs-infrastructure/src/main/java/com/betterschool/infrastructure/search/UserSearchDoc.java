package com.betterschool.infrastructure.search;

import lombok.Data;

/**
 * 用户索引文档：仅 id + 昵称检索；仅 NORMAL 状态入库。技术组件内部结构，领域层不引用
 */
@Data
public class UserSearchDoc {

    private String userId;
    private String nickname;
    private String status;
}
