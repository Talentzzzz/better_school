package com.betterschool.domain.user.service.auth;

import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.UserAuthResult;

/**
 * 用户认证领域服务：验证码发送、注册、登录。
 * 注册不建钱包（跨域动作由 case 层编排），login 保持纯 user 域
 */
public interface IUserAuthService {

    void sendCode(String phone);

    /** 注册（事务参与者，case 层包事务 + 建钱包）：保存用户 + 落注册消息，返回注册用户 */
    UserEntity register(String phone, String code, String nickname);

    UserAuthResult login(String phone, String code);

    /** 签发登录令牌（注册成功后由 case 层编排调用；登录路径在 login 内部已签发） */
    String issueToken(UserEntity userEntity);
}
