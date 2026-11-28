package com.betterschool.domain.user.service.profile;

import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.GenderEnum;
import com.betterschool.domain.user.model.valobj.UserStatusEnum;
import com.betterschool.types.common.SearchPageVO;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "用户不存在");
        }
        return userEntity;
    }

    @Override
    public long countReceivedLikes(String userId) {
        return userRepository.sumNormalPostLikeCount(userId);
    }

    @Override
    public boolean isLikedVisibleTo(String ownerId, String viewerId) {
        return userRepository.isLikedVisibleTo(ownerId, viewerId);
    }

    @Override
    public boolean isCommentsVisibleTo(String ownerId, String viewerId) {
        return userRepository.isCommentsVisibleTo(ownerId, viewerId);
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
        GenderEnum genderVO = GenderEnum.from(gender);
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

    @Override
    public SearchPageVO<UserEntity> searchUsers(String keyword, int page, int pageSize) {
        // 索引只负责命中与排序：命中 id 回库批量反查，按 id 顺序重排（IN 查询不保序），
        // 命中后状态已变化的实体在这里被过滤掉——本域才是「可搜」的最终判据
        SearchPageVO<String> idPage = userRepository.searchUserIds(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<String, UserEntity> byId = userRepository.listByIds(idPage.getItems()).stream()
                .filter(user -> user.getStatus() == UserStatusEnum.NORMAL)
                .collect(Collectors.toMap(UserEntity::getUserId, Function.identity()));
        return new SearchPageVO<>(idPage.getTotal(), idPage.getItems().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList());
    }

    @Override
    public void syncSearchDoc(String userId) {
        userRepository.syncUserSearchDoc(userId);
    }

    @Override
    public int rebuildSearchIndex() {
        return userRepository.rebuildUserSearchIndex();
    }

    /** 签名归一：trim 后空串＝清空（落 NULL）；超长报参数错误 */
    private String normalizeSignature(String signature) {
        if (signature == null) {
            return null;
        }
        String trimmed = signature.trim();
        if (trimmed.length() > SIGNATURE_MAX_LENGTH) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "签名最多 " + SIGNATURE_MAX_LENGTH + " 字");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
