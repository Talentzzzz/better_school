package com.betterschool.domain.search.model.valobj;

import lombok.Data;

/**
 * 用户索引文档：仅 id + 昵称检索；仅 NORMAL 状态入库
 */
@Data
public class UserSearchDocVO {

    private String userId;
    private String nickname;
    private String status;
}
