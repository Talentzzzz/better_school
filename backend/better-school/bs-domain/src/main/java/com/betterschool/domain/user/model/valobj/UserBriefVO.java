package com.betterschool.domain.user.model.valobj;

import lombok.Data;

/**
 * 用户展示信息的窄投影（跨域只读端口的返回值）：只带昵称头像，
 * 不把 phone/creditScore 等敏感与非展示字段带出 user 域。
 * 与 UserEntity 的区别是「投影」而非「完整实体」，只读不写。
 */
@Data
public class UserBriefVO {

    private String userId;
    private String nickname;
    private String avatar;
}
