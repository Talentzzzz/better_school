package com.betterschool.domain.chat.service.send;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeEnum;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.chat.model.valobj.DirectRoomPairVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeEnum;
import com.betterschool.domain.chat.model.valobj.RoomSourceEnum;
import com.betterschool.domain.chat.service.push.IChatPushService;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 发送用例实现：写路径的原子性（取号与消息、时间线写入同事务，回滚即无号无痕）由 IChatRepository
 * 的原子用例方法自带；重复提交撞 uk_client_msg 回查收敛同一条，两个时序（已落库快路径 / 并发未落库回滚后回查）全覆盖。
 * 用户存在性与卡片引用状态经 chat 自有 port 只读他域，判据（哪些状态算失效）留在本域
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSendServiceImpl implements IChatSendService {

    /** TEXT 载荷长度上限（spec §1 单一常量收敛） */
    public static final int MAX_TEXT_LENGTH = 500;

    private final IChatRepository chatRepository;
    private final IChatPushService chatPushService;
    private final ObjectMapper objectMapper;

    /** 本站图片访问前缀（minio public-url），IMAGE url 须以它开头 */
    @Value("${minio.public-url:}")
    private String imagePublicUrl;

    @Override
    public RoomEntity createDirectRoom(String userId, String targetUserId, RoomSourceEnum roomSourceEnum) {
        if (targetUserId == null || targetUserId.equals(userId)) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "不能与自己建立会话");
        }
        // 系统助手会话只能由系统侧发起（通知投递）：用户不能主动与系统账号建会话
        if ((targetUserId.equals(Constants.SYSTEM_USER_ID) || userId.equals(Constants.SYSTEM_USER_ID))
                && !userId.equals(Constants.SYSTEM_USER_ID)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "系统会话不可由用户发起");
        }
        // 目标用户是否存在由 case 层在进入本域前校验（跨域只读不在 domain 内做）
        // user_id 是 UUID 字符串后无数值序，配对键改按字符串字典序取规范序（仅用于撞键收敛，语义与数值 min/max 等价）
        DirectRoomPairVO directRoomPairVO = DirectRoomPairVO.canonicalOf(userId, targetUserId);

        // 首来源钉死：既有会话原样返回，带来源的二次进入（如先私信后从市集联系）不覆盖来源与创建人
        RoomEntity existingRoomEntity = chatRepository.findDirectRoom(directRoomPairVO.getUserMin(), directRoomPairVO.getUserMax());
        if (existingRoomEntity != null) {
            return existingRoomEntity;
        }
        RoomEntity roomEntity = new RoomEntity();
        roomEntity.setSource(roomSourceEnum);
        roomEntity.setCreatorId(userId);
        try {
            // 建会话 + 两名成员 + 配对行是一个原子用例（仓储内事务，配对行收尾兜幂等）
            return chatRepository.createDirectRoom(roomEntity, targetUserId);
        } catch (DuplicateKeyException e) {
            // 并发建会话撞 user_pair_room.uk_pair：幂等返回既有 room
            return chatRepository.findDirectRoom(directRoomPairVO.getUserMin(), directRoomPairVO.getUserMax());
        }
    }

    @Override
    public MessageEntity sendMessage(MessageEntity messageEntity, ChatUserVO chatUserVO) {
        String roomId = messageEntity.getRoomId();
        String userId = messageEntity.getFromUserId();
        RoomEntity roomEntity = chatRepository.findRoomById(roomId);
        if (roomEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "会话不存在");
        }
        if (chatRepository.findUserRoom(roomId, userId) == null) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "非会话成员");
        }
        // 系统助手会话不可回复：非系统账号向系统会话发消息一律拒绝（通知投递走 SYSTEM 类型，仅系统侧可发）
        if (!userId.equals(Constants.SYSTEM_USER_ID)
                && chatRepository.listRoomMembers(roomId).stream()
                .anyMatch(memberEntity -> Constants.SYSTEM_USER_ID.equals(memberEntity.getUserId()))) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "系统会话不可回复");
        }

        // 幂等快路径：同 client_msg_id 已落库（超时重发/双击），收敛原消息——
        // 须在载荷校验之前，重发语义不受目标失效影响
        MessageEntity existingEntity = chatRepository.findMessageByClientMsgId(roomId, messageEntity.getClientMsgId());
        if (existingEntity != null) {
            return existingEntity;
        }

        validateContent(messageEntity.getMsgType(), messageEntity.getContent(), messageEntity.getRefType(), messageEntity.getRefId());

        // 推送载荷在此组装（发送人昵称头像拼进 WS 推送，收端免二次反查）；
        // 取号 + 落消息 + 扇出是一个原子用例（仓储内事务），提交后立即推送
        ChatMessagePushEvent chatMessagePushEvent = ChatMessagePushEvent.builder()
                .roomId(roomId)
                .fromUserId(userId)
                .fromNickname(chatUserVO.getNickname())
                .fromAvatar(chatUserVO.getAvatar())
                .msgType(messageEntity.getMsgType())
                .content(messageEntity.getContent())
                .refType(messageEntity.getRefType())
                .refId(messageEntity.getRefId())
                .clientMsgId(messageEntity.getClientMsgId())
                .build();
        MessageEntity delivered;
        try {
            delivered = chatRepository.deliverMessage(messageEntity, chatMessagePushEvent);
        } catch (DuplicateKeyException e) {
            // 并发未落库时序：另一事务持有 uk 锁未提交，本事务取号与写入全部回滚后回查收敛
            MessageEntity originalEntity = chatRepository.findMessageByClientMsgId(roomId, messageEntity.getClientMsgId());
            if (originalEntity != null) {
                return originalEntity;
            }
            throw e;
        }
        // 事务已提交：直接推送（推送是加速路径，失败不重试；漏推由客户端 (roomId, seq) 增量补拉兜底）
        chatPushService.pushMessage(chatMessagePushEvent);
        return delivered;
    }

    // ===== 载荷校验（spec §1 类型语义） =====

    private void validateContent(MsgTypeEnum msgTypeVO, String content, CardRefTypeEnum cardRefTypeVO, String refId) {
        switch (msgTypeVO) {
            case TEXT -> {
                if (content == null || content.isBlank()) {
                    throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "消息内容不能为空");
                }
                if (content.length() > MAX_TEXT_LENGTH) {
                    throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "消息最长 " + MAX_TEXT_LENGTH + " 字");
                }
            }
            case IMAGE -> validateImageContent(content);
            case CARD -> validateCardContent(content, cardRefTypeVO, refId);
            case SYSTEM -> validateSystemContent(content);
        }
    }

    /** SYSTEM 载荷 = JSON{notificationId, title, content, refType?, refId?}：仅系统侧发送（发送方守卫在 sendMessage） */
    private void validateSystemContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "系统消息载荷不能为空");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "系统消息载荷须为 JSON");
        }
        if (node.path("notificationId").asText("").isBlank()
                || node.path("title").asText("").isBlank()
                || node.path("content").asText("").isBlank()) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "系统消息载荷不完整");
        }
    }

    private void validateImageContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "图片载荷不能为空");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "图片载荷须为 JSON");
        }
        String url = node.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "图片地址不能为空");
        }
        if (imagePublicUrl.isBlank() || !url.startsWith(imagePublicUrl + "/")) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "仅支持本站上传的图片");
        }
    }

    /**
     * 卡片载荷只校验形状：CARD 恒空、引用列必填。
     * 对象存在性与状态（post / idle 两域）由 case 层在进入本域前校验。
     */
    private void validateCardContent(String content, CardRefTypeEnum cardRefTypeVO, String refId) {
        if (content != null && !content.isBlank()) {
            // CARD 恒空（纯实时渲染无快照），引用走独立 ref 列
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "卡片消息不携带内容");
        }
        if (cardRefTypeVO == null || refId == null) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "卡片引用不完整");
        }
    }
}
