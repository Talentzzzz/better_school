package com.betterschool.domain.chat.service.send;

import com.betterschool.domain.chat.adapter.port.ICardRefQueryPort;
import com.betterschool.domain.chat.adapter.port.IUserQueryPort;
import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeVO;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.chat.model.valobj.DirectRoomPairVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.chat.model.valobj.RoomSourceEnum;
import com.betterschool.domain.community.model.valobj.PostStatusVO;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import com.betterschool.types.enums.ResponseCode;
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
    private final IUserQueryPort userQueryPort;
    private final ICardRefQueryPort cardRefQueryPort;
    private final ObjectMapper objectMapper;

    /** 本站图片访问前缀（minio public-url），IMAGE url 须以它开头 */
    @Value("${minio.public-url:}")
    private String imagePublicUrl;

    @Override
    public RoomEntity createDirectRoom(String userId, String targetUserId, RoomSourceEnum source) {
        if (targetUserId == null || targetUserId.equals(userId)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "不能与自己建立会话");
        }
        if (userQueryPort.findById(targetUserId) == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "用户不存在");
        }
        // user_id 是 UUID 字符串后无数值序，配对键改按字符串字典序取规范序（仅用于撞键收敛，语义与数值 min/max 等价）
        DirectRoomPairVO pairKey = DirectRoomPairVO.canonicalOf(userId, targetUserId);

        // 首来源钉死：既有会话原样返回，带来源的二次进入（如先私信后从市集联系）不覆盖来源与创建人
        RoomEntity existingEntity = chatRepository.findDirectRoom(pairKey.getUserMin(), pairKey.getUserMax());
        if (existingEntity != null) {
            return existingEntity;
        }
        RoomEntity roomEntity = new RoomEntity();
        roomEntity.setSource(source);
        roomEntity.setCreatorId(userId);
        try {
            // 建会话 + 两名成员 + 配对行是一个原子用例（仓储内事务，配对行收尾兜幂等）
            return chatRepository.createDirectRoom(roomEntity, targetUserId);
        } catch (DuplicateKeyException e) {
            // 并发建会话撞 user_pair_room.uk_pair：幂等返回既有 room
            return chatRepository.findDirectRoom(pairKey.getUserMin(), pairKey.getUserMax());
        }
    }

    @Override
    public MessageEntity sendMessage(MessageEntity messageEntity) {
        String roomId = messageEntity.getRoomId();
        String userId = messageEntity.getFromUserId();
        RoomEntity roomEntity = chatRepository.findRoomById(roomId);
        if (roomEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "会话不存在");
        }
        if (chatRepository.findUserRoom(roomId, userId) == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "非会话成员");
        }

        // 幂等快路径：同 client_msg_id 已落库（超时重发/双击），收敛原消息——
        // 须在载荷校验之前，重发语义不受目标失效影响
        MessageEntity existingEntity = chatRepository.findMessageByClientMsgId(roomId, messageEntity.getClientMsgId());
        if (existingEntity != null) {
            return existingEntity;
        }

        validateContent(messageEntity.getMsgType(), messageEntity.getContent(), messageEntity.getRefType(), messageEntity.getRefId());

        try {
            // 推送载荷在此组装（发送人昵称头像拼进 WS 推送，收端免二次反查）；
            // 取号 + 落消息 + 扇出 + outbox 是一个原子用例（仓储内事务）
            ChatUserVO sender = userQueryPort.findById(userId);
            ChatMessagePushEvent chatMessagePushEvent = ChatMessagePushEvent.builder()
                    .roomId(roomId)
                    .fromUserId(userId)
                    .fromNickname(sender.getNickname())
                    .fromAvatar(sender.getAvatar())
                    .msgType(messageEntity.getMsgType())
                    .content(messageEntity.getContent())
                    .refType(messageEntity.getRefType())
                    .refId(messageEntity.getRefId())
                    .clientMsgId(messageEntity.getClientMsgId())
                    .build();
            return chatRepository.deliverMessage(messageEntity, chatMessagePushEvent);
        } catch (DuplicateKeyException e) {
            // 并发未落库时序：另一事务持有 uk 锁未提交，本事务取号与写入全部回滚后回查收敛
            MessageEntity originalEntity = chatRepository.findMessageByClientMsgId(roomId, messageEntity.getClientMsgId());
            if (originalEntity != null) {
                return originalEntity;
            }
            throw e;
        }
    }

    // ===== 载荷校验（spec §1 类型语义） =====

    private void validateContent(MsgTypeVO msgType, String content, CardRefTypeVO refType, String refId) {
        switch (msgType) {
            case TEXT -> {
                if (content == null || content.isBlank()) {
                    throw new BusinessException(ResponseCode.PARAM_ERROR, "消息内容不能为空");
                }
                if (content.length() > MAX_TEXT_LENGTH) {
                    throw new BusinessException(ResponseCode.PARAM_ERROR, "消息最长 " + MAX_TEXT_LENGTH + " 字");
                }
            }
            case IMAGE -> validateImageContent(content);
            case CARD -> validateCardContent(content, refType, refId);
        }
    }

    private void validateImageContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "图片载荷不能为空");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "图片载荷须为 JSON");
        }
        String url = node.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "图片地址不能为空");
        }
        if (imagePublicUrl.isBlank() || !url.startsWith(imagePublicUrl + "/")) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "仅支持本站上传的图片");
        }
    }

    /** 发送时仅校验对象存在且未被删除/拒绝（已售、审核中均可发）；判据留在这里，port 只给状态 */
    private void validateCardContent(String content, CardRefTypeVO refType, String refId) {
        if (content != null && !content.isBlank()) {
            // CARD 恒空（纯实时渲染无快照），引用走独立 ref 列
            throw new BusinessException(ResponseCode.PARAM_ERROR, "卡片消息不携带内容");
        }
        if (refType == null || refId == null) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "卡片引用不完整");
        }
        switch (refType) {
            case POST -> {
                PostStatusVO status = cardRefQueryPort.findPostStatus(refId);
                if (status == null) {
                    throw new BusinessException(ResponseCode.NOT_FOUND, "卡片对象不存在");
                }
                // 非 DELETED/REJECTED/DRAFT（spec §1）：待审帖子也可发卡
                if (status == PostStatusVO.DELETED || status == PostStatusVO.REJECTED
                        || status == PostStatusVO.DRAFT) {
                    throw new BusinessException(ResponseCode.BUSINESS_ERROR, "内容已失效");
                }
            }
            case PRODUCT -> {
                // 业务 id 是 UUID 字符串，refId 直传（uuid-biz-id 票 05）
                ProductStatusVO status = cardRefQueryPort.findProductStatus(refId);
                if (status == null) {
                    throw new BusinessException(ResponseCode.NOT_FOUND, "卡片对象不存在");
                }
                if (status == ProductStatusVO.DELETED || status == ProductStatusVO.REJECTED) {
                    throw new BusinessException(ResponseCode.BUSINESS_ERROR, "内容已失效");
                }
            }
            case ERRAND -> {
                ErrandStatusVO status = cardRefQueryPort.findErrandStatus(refId);
                if (status == null) {
                    throw new BusinessException(ResponseCode.NOT_FOUND, "卡片对象不存在");
                }
                if (status == ErrandStatusVO.REJECTED || status == ErrandStatusVO.CANCELLED) {
                    throw new BusinessException(ResponseCode.BUSINESS_ERROR, "内容已失效");
                }
            }
        }
    }
}
