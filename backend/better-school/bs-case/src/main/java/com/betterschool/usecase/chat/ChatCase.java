package com.betterschool.usecase.chat;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeEnum;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeEnum;
import com.betterschool.domain.chat.model.valobj.RoomMemberVO;
import com.betterschool.domain.chat.model.valobj.RoomSourceEnum;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelinePageVO;
import com.betterschool.domain.chat.service.query.IChatQueryService;
import com.betterschool.domain.chat.service.send.IChatSendService;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.valobj.ProductStatusEnum;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.valobj.PostStatusEnum;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.service.profile.IUserProfileService;
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

/**
 * chat 用例编排：会话读写的跨域部分统一收在这里。
 * <p>
 * chat 域只做会话/消息自身的规则；用户资料（昵称头像、隐私）与卡片引用对象（post / 商品）的状态
 * 都属于他域，由本层取回后再进入 chat 域，domain 内不再出现任何跨域 port。
 * <p>
 * 实时推送不经过本层：写路径（发送/已读/撤回）的事务提交后由域服务直接推送（成员扇出规则归 chat 域
 * push 服务），本层只做业务入站编排。
 */
@Service
@RequiredArgsConstructor
public class ChatCase {

    private final IChatSendService chatSendService;
    private final IChatQueryService chatQueryService;
    private final IUserProfileService userProfileService;
    private final IPostService postService;
    private final IIdleService idleService;

    /** 建会话：目标用户存在性由用例层校验（chat 域不读 user 域） */
    public RoomEntity createDirectRoom(String userId, String targetUserId, RoomSourceEnum roomSourceEnum) {
        if (targetUserId == null || userProfileService.listBasic(List.of(targetUserId)).isEmpty()) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "用户不存在");
        }
        return chatSendService.createDirectRoom(userId, targetUserId, roomSourceEnum);
    }

    /** 发消息：发送人资料与卡片引用状态在进入 chat 域前补齐/校验 */
    public MessageEntity sendMessage(MessageEntity messageEntity) {
        if (messageEntity.getFromUserId() == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "用户不存在");
        }
        ChatUserVO chatUserVO = usersOf(List.of(messageEntity.getFromUserId())).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResponseCodeEnum.NOT_FOUND, "用户不存在"));
        validateCardRef(messageEntity);
        return chatSendService.sendMessage(messageEntity, chatUserVO);
    }

    public List<RoomListItemVO> listRooms(String userId, int page, int pageSize) {
        List<RoomListItemVO> rooms = chatQueryService.listRooms(userId, page, pageSize);
        List<String> peerIds = rooms.stream()
                .map(RoomListItemVO::getPeerUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, ChatUserVO> users = usersOf(peerIds).stream()
                .collect(Collectors.toMap(ChatUserVO::getUserId, Function.identity()));
        for (RoomListItemVO roomListItemVO : rooms) {
            ChatUserVO chatUserVO = users.get(roomListItemVO.getPeerUserId());
            roomListItemVO.setPeerNickname(chatUserVO == null ? null : chatUserVO.getNickname());
            roomListItemVO.setPeerAvatar(chatUserVO == null ? null : chatUserVO.getAvatar());
        }
        return rooms;
    }

    public List<RoomMemberVO> listMembers(String roomId, String userId) {
        List<RoomMemberVO> members = chatQueryService.listMembers(roomId, userId);
        List<String> memberIds = members.stream()
                .map(RoomMemberVO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, ChatUserVO> users = usersOf(memberIds).stream()
                .collect(Collectors.toMap(ChatUserVO::getUserId, Function.identity()));
        for (RoomMemberVO roomMemberVO : members) {
            ChatUserVO chatUserVO = users.get(roomMemberVO.getUserId());
            roomMemberVO.setNickname(chatUserVO == null ? null : chatUserVO.getNickname());
            roomMemberVO.setAvatar(chatUserVO == null ? null : chatUserVO.getAvatar());
        }
        return members;
    }

    public List<MessageEntity> listMessages(String roomId, String userId, Long beforeSeq, Integer limit) {
        return chatQueryService.listMessages(roomId, userId, beforeSeq, limit);
    }

    public TimelinePageVO pullTimeline(String userId, Long deliverySeqCursor, Integer limit) {
        return chatQueryService.pullTimeline(userId, deliverySeqCursor, limit);
    }

    /**
     * 卡片引用校验（spec §1）：对象存在且未被删除/拒绝；已售、审核中均可发。
     * 状态判据留在这里，post / idle 两域只提供自己的读模型。
     */
    private void validateCardRef(MessageEntity messageEntity) {
        if (messageEntity.getMsgType() != MsgTypeEnum.CARD) {
            return;
        }
        CardRefTypeEnum cardRefTypeVO = messageEntity.getRefType();
        String refId = messageEntity.getRefId();
        if (cardRefTypeVO == null || refId == null) {
            return; // 形状错误由 chat 域统一抛 PARAM_ERROR
        }
        switch (cardRefTypeVO) {
            case POST -> {
                PostEntity postEntity = postService.getPostEntity(refId);
                if (postEntity == null) {
                    throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "卡片对象不存在");
                }
                if (postEntity.getStatus() == PostStatusEnum.DELETED || postEntity.getStatus() == PostStatusEnum.DRAFT) {
                    throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "内容已失效");
                }
            }
            case PRODUCT -> {
                ProductEntity productEntity = idleService.getProductEntity(refId);
                if (productEntity == null) {
                    throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "卡片对象不存在");
                }
                if (productEntity.getStatus() == ProductStatusEnum.DELETED) {
                    throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "内容已失效");
                }
            }
        }
    }

    private List<ChatUserVO> usersOf(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userProfileService.listBasic(userIds).stream().map(this::toChatUser).toList();
    }

    private ChatUserVO toChatUser(UserEntity userEntity) {
        ChatUserVO chatUserVO = new ChatUserVO();
        chatUserVO.setUserId(userEntity.getUserId());
        chatUserVO.setNickname(userEntity.getNickname());
        chatUserVO.setAvatar(userEntity.getAvatar());
        return chatUserVO;
    }
}
