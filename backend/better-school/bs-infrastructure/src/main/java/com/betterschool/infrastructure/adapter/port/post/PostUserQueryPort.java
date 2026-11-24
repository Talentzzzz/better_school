package com.betterschool.infrastructure.adapter.port.post;

import com.betterschool.domain.post.adapter.port.IUserQueryPort;
import com.betterschool.domain.post.model.valobj.PostUserVO;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用户只读查询端口实现（post 域）：只读 user 域的读路径，把昵称与两个隐私开关取回来交 community 判定。
 * 落位在 adapter/port（不是 repository），跨域只读边的伸手点集中在这里
 */
@Component
@RequiredArgsConstructor
public class PostUserQueryPort implements IUserQueryPort {

    private final IUserRepository userRepository;

    @Override
    public PostUserVO findById(String userId) {
        UserEntity userEntity = userRepository.findById(userId);
        if (userEntity == null) {
            return null;
        }
        PostUserVO userVO = new PostUserVO();
        userVO.setUserId(userEntity.getUserId());
        userVO.setNickname(userEntity.getNickname());
        userVO.setShowLiked(userEntity.getShowLiked());
        userVO.setShowComments(userEntity.getShowComments());
        return userVO;
    }
}
