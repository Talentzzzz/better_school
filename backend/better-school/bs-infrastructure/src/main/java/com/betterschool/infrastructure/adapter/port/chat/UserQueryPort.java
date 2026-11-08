package com.betterschool.infrastructure.adapter.port.chat;

import com.betterschool.domain.chat.adapter.port.IUserQueryPort;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * chat 用户只读查询端口实现：只读 user 域，翻译成 chat 域内 VO（UserEntity 不出本层）。
 * 落位在 adapter/port（不是 repository），跨域只读边的伸手点集中在这里
 */
@Component
@RequiredArgsConstructor
public class UserQueryPort implements IUserQueryPort {

    private final IUserRepository userRepository;

    @Override
    public ChatUserVO findById(String userId) {
        return toVO(userRepository.findById(userId));
    }

    @Override
    public List<ChatUserVO> listByIds(List<String> userIds) {
        return userRepository.listByIds(userIds).stream().map(this::toVO).toList();
    }

    private ChatUserVO toVO(UserEntity userEntity) {
        if (userEntity == null) {
            return null;
        }
        ChatUserVO chatUser = new ChatUserVO();
        chatUser.setUserId(userEntity.getUserId());
        chatUser.setNickname(userEntity.getNickname());
        chatUser.setAvatar(userEntity.getAvatar());
        return chatUser;
    }
}
