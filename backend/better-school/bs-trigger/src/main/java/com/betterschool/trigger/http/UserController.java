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
    public Response<Void> sendCode(@Valid @RequestBody SendCodeRequestDTO request) {
        userAuthService.sendCode(request.getPhone());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/register")
    public Response<UserAuthResponseDTO> register(@Valid @RequestBody UserRegisterRequestDTO request) {
        UserAuthResult authResult = userRegisterCase.register(request.getPhone(), request.getCode(), request.getNickname());
        return Response.ok(toAuthResponseDTO(authResult));
    }

    @Override
    @PostMapping("/login")
    public Response<UserAuthResponseDTO> login(@Valid @RequestBody UserLoginRequestDTO request) {
        UserAuthResult authResult = userAuthService.login(request.getPhone(), request.getCode());
        return Response.ok(toAuthResponseDTO(authResult));
    }

    @Override
    @GetMapping("/{userId}/profile")
    public Response<PublicUserProfileResponseDTO> getProfile(@PathVariable String userId) {
        return Response.ok(toPublicProfileDTO(userProfileService.getProfile(userId)));
    }

    @Override
    @PutMapping("/profile")
    public Response<PublicUserProfileResponseDTO> updateProfile(@Valid @RequestBody UserProfileUpdateRequestDTO request) {
        UserEntity userEntity = userProfileService.updateProfile(SecurityUtils.currentUserId(),
                request.getSignature(), request.getGender());
        return Response.ok(toPublicProfileDTO(userEntity));
    }

    @Override
    @PutMapping("/privacy")
    public Response<UserPrivacyResponseDTO> updatePrivacy(@Valid @RequestBody UserPrivacyUpdateRequestDTO request) {
        UserEntity userEntity = userProfileService.updatePrivacy(SecurityUtils.currentUserId(),
                request.getShowLiked(), request.getShowComments());
        UserPrivacyResponseDTO dto = new UserPrivacyResponseDTO();
        dto.setShowLiked(Boolean.TRUE.equals(userEntity.getShowLiked()));
        dto.setShowComments(Boolean.TRUE.equals(userEntity.getShowComments()));
        return Response.ok(dto);
    }

    private PublicUserProfileResponseDTO toPublicProfileDTO(UserEntity userEntity) {
        PublicUserProfileResponseDTO dto = new PublicUserProfileResponseDTO();
        dto.setUserId(userEntity.getUserId());
        dto.setNickname(userEntity.getNickname());
        dto.setAvatar(userEntity.getAvatar());
        dto.setSignature(userEntity.getSignature());
        dto.setGender(userEntity.getGender() == null ? null : userEntity.getGender().name());
        dto.setReceivedLikeCount(userProfileService.countReceivedLikes(userEntity.getUserId()));
        dto.setShowLiked(!Boolean.FALSE.equals(userEntity.getShowLiked()));
        dto.setShowComments(!Boolean.FALSE.equals(userEntity.getShowComments()));
        return dto;
    }

    private UserAuthResponseDTO toAuthResponseDTO(UserAuthResult authResult) {
        UserAuthResponseDTO dto = new UserAuthResponseDTO();
        dto.setToken(authResult.getToken());
        dto.setUserInfo(toResponseDTO(authResult.getUserEntity()));
        return dto;
    }

    private UserResponseDTO toResponseDTO(UserEntity userEntity) {
        UserResponseDTO dto = new UserResponseDTO();
        dto.setUserId(userEntity.getUserId());
        dto.setPhone(userEntity.getPhone());
        dto.setNickname(userEntity.getNickname());
        dto.setAvatar(userEntity.getAvatar());
        dto.setRole(userEntity.getRole().name());
        dto.setStatus(userEntity.getStatus().name());
        dto.setCreditScore(userEntity.getCreditScore());
        dto.setLastLoginAt(userEntity.getLastLoginAt());
        return dto;
    }
}
