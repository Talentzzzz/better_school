package com.betterschool.trigger.http;

import com.betterschool.api.dto.PublicUserProfileResponseDTO;
import com.betterschool.api.dto.SendCodeRequestDTO;
import com.betterschool.api.dto.UserLoginRequestDTO;
import com.betterschool.api.dto.UserPrivacyResponseDTO;
import com.betterschool.api.dto.UserPrivacyUpdateRequestDTO;
import com.betterschool.api.dto.UserProfileUpdateRequestDTO;
import com.betterschool.api.dto.UserRegisterRequestDTO;
import com.betterschool.api.dto.UserResponseDTO;
import com.betterschool.api.IUserService;
import com.betterschool.api.dto.UserAuthResponseDTO;
import com.betterschool.api.response.Response;
import com.betterschool.usecase.user.UserRegisterCase;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.UserAuthResult;
import com.betterschool.domain.user.service.auth.IUserAuthService;
import com.betterschool.domain.user.service.profile.IUserProfileService;
import com.betterschool.trigger.http.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 HTTP 入口：实现 api 层契约，注册走 case（user + wallet 编排），登录/验证码委托领域服务
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController implements IUserService {

    private final IUserAuthService userAuthService;
    private final IUserProfileService userProfileService;
    private final UserRegisterCase userRegisterCase;

    @Override
    @PostMapping("/send-code")
    public Response<Void> sendCode(@Valid @RequestBody SendCodeRequestDTO sendCodeRequestDTO) {
        userAuthService.sendCode(sendCodeRequestDTO.getPhone());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/register")
    public Response<UserAuthResponseDTO> register(@Valid @RequestBody UserRegisterRequestDTO userRegisterRequestDTO) {
        UserAuthResult userAuthResult = userRegisterCase.register(userRegisterRequestDTO.getPhone(), userRegisterRequestDTO.getCode(), userRegisterRequestDTO.getNickname());
        return Response.ok(toAuthResponseDTO(userAuthResult));
    }

    @Override
    @PostMapping("/login")
    public Response<UserAuthResponseDTO> login(@Valid @RequestBody UserLoginRequestDTO userLoginRequestDTO) {
        UserAuthResult userAuthResult = userAuthService.login(userLoginRequestDTO.getPhone(), userLoginRequestDTO.getCode());
        return Response.ok(toAuthResponseDTO(userAuthResult));
    }

    @Override
    @GetMapping("/{userId}/profile")
    public Response<PublicUserProfileResponseDTO> getProfile(@PathVariable String userId) {
        return Response.ok(toPublicProfileDTO(userProfileService.getProfile(userId)));
    }

    @Override
    @PutMapping("/profile")
    public Response<PublicUserProfileResponseDTO> updateProfile(@Valid @RequestBody UserProfileUpdateRequestDTO userProfileUpdateRequestDTO) {
        UserEntity userEntity = userProfileService.updateProfile(SecurityUtils.currentUserId(),
                userProfileUpdateRequestDTO.getSignature(), userProfileUpdateRequestDTO.getGender());
        return Response.ok(toPublicProfileDTO(userEntity));
    }

    @Override
    @PutMapping("/privacy")
    public Response<UserPrivacyResponseDTO> updatePrivacy(@Valid @RequestBody UserPrivacyUpdateRequestDTO userPrivacyUpdateRequestDTO) {
        UserEntity userEntity = userProfileService.updatePrivacy(SecurityUtils.currentUserId(),
                userPrivacyUpdateRequestDTO.getShowLiked(), userPrivacyUpdateRequestDTO.getShowComments());
        UserPrivacyResponseDTO userPrivacyResponseDTO = new UserPrivacyResponseDTO();
        userPrivacyResponseDTO.setShowLiked(Boolean.TRUE.equals(userEntity.getShowLiked()));
        userPrivacyResponseDTO.setShowComments(Boolean.TRUE.equals(userEntity.getShowComments()));
        return Response.ok(userPrivacyResponseDTO);
    }

    private PublicUserProfileResponseDTO toPublicProfileDTO(UserEntity userEntity) {
        PublicUserProfileResponseDTO publicUserProfileResponseDTO = new PublicUserProfileResponseDTO();
        publicUserProfileResponseDTO.setUserId(userEntity.getUserId());
        publicUserProfileResponseDTO.setNickname(userEntity.getNickname());
        publicUserProfileResponseDTO.setAvatar(userEntity.getAvatar());
        publicUserProfileResponseDTO.setSignature(userEntity.getSignature());
        publicUserProfileResponseDTO.setGender(userEntity.getGender() == null ? null : userEntity.getGender().name());
        publicUserProfileResponseDTO.setReceivedLikeCount(userProfileService.countReceivedLikes(userEntity.getUserId()));
        publicUserProfileResponseDTO.setShowLiked(!Boolean.FALSE.equals(userEntity.getShowLiked()));
        publicUserProfileResponseDTO.setShowComments(!Boolean.FALSE.equals(userEntity.getShowComments()));
        return publicUserProfileResponseDTO;
    }

    private UserAuthResponseDTO toAuthResponseDTO(UserAuthResult userAuthResult) {
        UserAuthResponseDTO userAuthResponseDTO = new UserAuthResponseDTO();
        userAuthResponseDTO.setToken(userAuthResult.getToken());
        userAuthResponseDTO.setUserInfo(toResponseDTO(userAuthResult.getUserEntity()));
        return userAuthResponseDTO;
    }

    private UserResponseDTO toResponseDTO(UserEntity userEntity) {
        UserResponseDTO userResponseDTO = new UserResponseDTO();
        userResponseDTO.setUserId(userEntity.getUserId());
        userResponseDTO.setPhone(userEntity.getPhone());
        userResponseDTO.setNickname(userEntity.getNickname());
        userResponseDTO.setAvatar(userEntity.getAvatar());
        userResponseDTO.setRole(userEntity.getRole().name());
        userResponseDTO.setStatus(userEntity.getStatus().name());
        userResponseDTO.setCreditScore(userEntity.getCreditScore());
        userResponseDTO.setLastLoginAt(userEntity.getLastLoginAt());
        return userResponseDTO;
    }
}
