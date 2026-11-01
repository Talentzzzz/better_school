package com.betterschool.api;

import com.betterschool.api.dto.PublicUserProfileResponseDTO;
import com.betterschool.api.dto.SendCodeRequestDTO;
import com.betterschool.api.dto.UserAuthResponseDTO;
import com.betterschool.api.dto.UserLoginRequestDTO;
import com.betterschool.api.dto.UserPrivacyResponseDTO;
import com.betterschool.api.dto.UserPrivacyUpdateRequestDTO;
import com.betterschool.api.dto.UserProfileUpdateRequestDTO;
import com.betterschool.api.dto.UserRegisterRequestDTO;
import com.betterschool.api.response.Response;

/**
 * 用户服务契约：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface IUserService {

    Response<Void> sendCode(SendCodeRequestDTO request);

    Response<UserAuthResponseDTO> register(UserRegisterRequestDTO request);

    Response<UserAuthResponseDTO> login(UserLoginRequestDTO request);

    /** 公开资料（免登录；不含手机号） */
    Response<PublicUserProfileResponseDTO> getProfile(String userId);

    /** 编辑资料（需登录；签名 ≤40 字、性别 MALE/FEMALE/不展示） */
    Response<PublicUserProfileResponseDTO> updateProfile(UserProfileUpdateRequestDTO request);

    /** 隐私设置（需登录；两个全局开关，仅他视生效） */
    Response<UserPrivacyResponseDTO> updatePrivacy(UserPrivacyUpdateRequestDTO request);
}
