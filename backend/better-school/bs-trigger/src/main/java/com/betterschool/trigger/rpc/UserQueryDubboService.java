package com.betterschool.trigger.rpc;

import com.betterschool.api.IUserQueryService;
import com.betterschool.api.dto.UserBasicDTO;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.service.profile.IUserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.Collection;
import java.util.List;

/**
 * 用户只读查询的 Dubbo Provider（同步边）：营销三服务经此取昵称 / 头像 / 注册时间。
 * <p>
 * 只读、无事务、不反向调用营销服务；域类型在此翻译成 bs-api 的 DTO，域层不认识 Dubbo。
 * 契约变更纪律见 backend/docs/architecture/marketing-services.md。
 */
@Slf4j
@DubboService
@RequiredArgsConstructor
public class UserQueryDubboService implements IUserQueryService {

    private final IUserProfileService userProfileService;

    @Override
    public UserBasicDTO getBasic(String userId) {
        if (userId == null) {
            return null;
        }
        List<UserEntity> userEntities = userProfileService.listBasic(List.of(userId));
        return userEntities.isEmpty() ? null : toDTO(userEntities.get(0));
    }

    @Override
    public List<UserBasicDTO> getBasicBatch(Collection<String> userIds) {
        return userProfileService.listBasic(userIds).stream()
                .map(UserQueryDubboService::toDTO)
                .toList();
    }

    private static UserBasicDTO toDTO(UserEntity userEntity) {
        UserBasicDTO dto = new UserBasicDTO();
        dto.setUserId(userEntity.getUserId());
        dto.setNickname(userEntity.getNickname());
        dto.setAvatar(userEntity.getAvatar());
        dto.setCreatedAt(userEntity.getCreatedAt());
        return dto;
    }
}
