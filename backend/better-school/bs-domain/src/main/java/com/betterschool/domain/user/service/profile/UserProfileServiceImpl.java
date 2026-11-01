package com.betterschool.domain.user.service.profile;

import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.GenderVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements IUserProfileService {

    /** 签名长度上限（按字符计，与 bs_user.signature varchar(60) 留足余量） */
    private static final int SIGNATURE_MAX_LENGTH = 40;

    private final IUserRepository userRepository;

    @Override
    public UserEntity getProfile(String userId) {
        UserEntity userEntity = userRepository.findById(userId);
        if (userEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "用户不存在");
        }
        return userEntity;
    }

    @Override
    public long countReceivedLikes(String userId) {
        return userRepository.sumNormalPostLikeCount(userId);
    }

    @Override
    public List<UserEntity> listBasic(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userRepository.listByIds(List.copyOf(userIds));
    }

    @Override
    public UserEntity updateProfile(String userId, String signature, String gender) {
        UserEntity userEntity = getProfile(userId);
        String normalized = normalizeSignature(signature);
        GenderVO genderVO = GenderVO.from(gender);
        userRepository.updateProfile(userId, normalized, genderVO == null ? null : genderVO.name());
        userEntity.setSignature(normalized);
        userEntity.setGender(genderVO);
        return userEntity;
    }

    @Override
    public UserEntity updatePrivacy(String userId, Boolean showLiked, Boolean showComments) {
        UserEntity userEntity = getProfile(userId);
        boolean nextLiked = showLiked != null ? showLiked : Boolean.TRUE.equals(userEntity.getShowLiked());
        boolean nextComments = showComments != null ? showComments : Boolean.TRUE.equals(userEntity.getShowComments());
        userRepository.updatePrivacy(userId, nextLiked, nextComments);
        userEntity.setShowLiked(nextLiked);
        userEntity.setShowComments(nextComments);
        return userEntity;
    }

    /** 签名归一：trim 后空串＝清空（落 NULL）；超长报参数错误 */
    private String normalizeSignature(String signature) {
        if (signature == null) {
            return null;
        }
        String trimmed = signature.trim();
        if (trimmed.length() > SIGNATURE_MAX_LENGTH) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "签名最多 " + SIGNATURE_MAX_LENGTH + " 字");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
