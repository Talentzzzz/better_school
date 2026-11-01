package com.betterschool.domain.user.model.valobj;

import com.betterschool.domain.user.model.entity.UserEntity;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录/注册认证结果：用户 + 登录令牌
 */
@Data
@AllArgsConstructor
public class UserAuthResult {

    private UserEntity userEntity;
    private String token;
}
