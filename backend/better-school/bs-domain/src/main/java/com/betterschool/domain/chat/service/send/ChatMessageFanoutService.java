package com.betterschool.domain.chat.service.send;

import com.betterschool.domain.chat.adapter.port.IUserQueryPort;
import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeVO;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushMessage;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 消息扇出核心（发送链路的组装段）：只构造消息实体、裁会话列表预览、备推送载荷，
 * 落库与扇出的原子性交给 {@link IChatRepository#deliverMessage}（仓储内事务：
 * 取号 → 落消息 → timeline 逐成员扇出 → 清 hidden 弹回 → 刷成员行列表副本 → outbox）。
 * 只做组装不做校验（成员/载荷校验属各用例）
 */
@Service
@RequiredArgsConstructor
public class ChatMessageFanoutService {

    /** 会话列表预览文案截断长度 */
    private static final int PREVIEW_MAX_LENGTH = 200;

    private static final String PREVIEW_IMAGE = "[图片]";
    private static final String PREVIEW_PRODUCT = "[商品]";
    private static final String PREVIEW_POST = "[帖子]";
    private static final String PREVIEW_ERRAND = "[跑腿卡片]";

    private final IChatRepository chatRepository;
    private final IUserQueryPort userQueryPort;

    /**
     * 组装并落库一条消息、扇出时间线（原子性由仓储保证）。
     *
     * @param fromUserId 发送者
     * @return 落库后的完整消息体（含 roomId/seq/messageId/createdAt）
     */
    public MessageEntity deliver(String roomId, String fromUserId, String clientMsgId, MsgTypeVO msgType,
                                 String content, CardRefTypeVO refType, String refId) {
        MessageEntity message = new MessageEntity();
        message.setRoomId(roomId);
        message.setFromUserId(fromUserId);
        message.setMsgType(msgType);
        message.setContent(content);
        message.setClientMsgId(clientMsgId);
        message.setRefType(refType);
        message.setRefId(refId);

        ChatUserVO sender = userQueryPort.findById(fromUserId);
        ChatMessagePushMessage pushMessage = ChatMessagePushMessage.builder()
                .roomId(roomId)
                .fromUserId(fromUserId)
                .fromNickname(sender.getNickname())
                .fromAvatar(sender.getAvatar())
                .msgType(msgType)
                .content(content)
                .refType(refType)
                .refId(refId)
                .clientMsgId(clientMsgId)
                .build();

        return chatRepository.deliverMessage(message, previewOf(msgType, content, refType), pushMessage);
    }

    /** 会话列表预览：TEXT 原文截 200 / IMAGE [图片] / CARD 类型标签 */
    private String previewOf(MsgTypeVO msgType, String content, CardRefTypeVO refType) {
        return switch (msgType) {
            case TEXT -> content.length() > PREVIEW_MAX_LENGTH ? content.substring(0, PREVIEW_MAX_LENGTH) : content;
            case IMAGE -> PREVIEW_IMAGE;
            case CARD -> switch (refType) {
                case PRODUCT -> PREVIEW_PRODUCT;
                case POST -> PREVIEW_POST;
                case ERRAND -> PREVIEW_ERRAND;
            };
        };
    }
}
