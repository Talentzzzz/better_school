package com.betterschool.infrastructure.adapter.port.community;

import com.betterschool.domain.community.adapter.port.IUserQueryPort;
import com.betterschool.domain.community.model.valobj.CommunityUserVO;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用户只读查询端口实现（community 域）：只读 user 域的读路径，把昵称与两个隐私开关取回来交 community 判定。
 * 落位在 adapter/port（不是 repository），跨域只读边的伸手点集中在这里
 */
@Component
@RequiredArgsConstructor
public class CommunityUserQueryPort implements IUserQueryPort {

    private final IUserRepository userRepository;

    @Override
    public CommunityUserVO findById(String userId) {
        UserEntity userEntity = userRepository.findById(userId);
        if (userEntity == null) {
            return null;
        }
        CommunityUserVO userVO = new CommunityUserVO();
        userVO.setUserId(userEntity.getUserId());
        userVO.setNickname(userEntity.getNickname());
        userVO.setShowLiked(userEntity.getShowLiked());
        userVO.setShowComments(userEntity.getShowComments());
        return userVO;
    }
}
