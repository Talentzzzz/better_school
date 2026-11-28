package com.betterschool.trigger.http;

import com.betterschool.api.IChatService;
import com.betterschool.api.dto.ChatMembersResponseDTO;
import com.betterschool.api.dto.ChatMessageResponseDTO;
import com.betterschool.api.dto.ChatRoomListResponseDTO;
import com.betterschool.api.dto.ChatRoomResponseDTO;
import com.betterschool.api.dto.ChatRoomListItemResponseDTO;
import com.betterschool.api.dto.ChatTimelineResponseDTO;
import com.betterschool.api.dto.CreateDirectRoomRequestDTO;
import com.betterschool.api.dto.SendMessageRequestDTO;
import com.betterschool.api.response.Response;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeEnum;
import com.betterschool.domain.chat.model.valobj.MsgTypeEnum;
import com.betterschool.domain.chat.model.valobj.RoomMemberVO;
import com.betterschool.domain.chat.model.valobj.RoomSourceEnum;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelinePageVO;
import com.betterschool.domain.chat.service.recall.IChatRecallService;
import com.betterschool.domain.chat.service.read.IChatReadService;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.usecase.chat.ChatCase;
import com.betterschool.domain.user.model.valobj.UserBriefVO;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * chat 会话 HTTP 入口（票 02 发送链路 + 票 03 读路径）
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController implements IChatService {

    /** 列表分页默认值与硬顶 */
    private static final int MAX_PAGE_SIZE = 200;

    private final ChatCase chatCase;
    private final IChatReadService chatReadService;
    private final IChatRecallService chatRecallService;
    private final IUserRepository userRepository;

    @Override
    @PostMapping("/rooms/direct")
    public Response<ChatRoomResponseDTO> createDirectRoom(@Valid @RequestBody CreateDirectRoomRequestDTO createDirectRoomRequestDTO) {
        RoomEntity roomEntity = chatCase.createDirectRoom(SecurityUtils.currentUserId(),
                createDirectRoomRequestDTO.getTargetUserId(), parseRoomSource(createDirectRoomRequestDTO.getSource()));
        ChatRoomResponseDTO chatRoomResponseDTO = new ChatRoomResponseDTO();
        chatRoomResponseDTO.setRoomId(roomEntity.getRoomId());
        return Response.ok(chatRoomResponseDTO);
    }

    @Override
    @PostMapping("/rooms/messages")
    public Response<ChatMessageResponseDTO> sendMessage(@Valid @RequestBody SendMessageRequestDTO sendMessageRequestDTO) {
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setRoomId(sendMessageRequestDTO.getRoomId());
        messageEntity.setFromUserId(SecurityUtils.currentUserId());
        messageEntity.setClientMsgId(sendMessageRequestDTO.getClientMsgId());
        messageEntity.setMsgType(parseMsgType(sendMessageRequestDTO.getMsgType()));
        messageEntity.setContent(sendMessageRequestDTO.getContent());
        messageEntity.setRefType(parseRefType(sendMessageRequestDTO.getRefType()));
        messageEntity.setRefId(sendMessageRequestDTO.getRefId());
        MessageEntity messageEntity = chatCase.sendMessage(messageEntity);
        ChatMessageResponseDTO chatMessageResponseDTO = toMessageResponseDTO(messageEntity, null);
        fillSenderInfo(chatMessageResponseDTO, messageEntity.getFromUserId());
        return Response.ok(chatMessageResponseDTO);
    }

    @Override
    @GetMapping("/rooms")
    public Response<ChatRoomListResponseDTO> listRooms(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "50") int pageSize) {
        int effectivePage = Math.max(1, page);
        int effectivePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        List<RoomListItemVO> rooms = chatCase.listRooms(SecurityUtils.currentUserId(),
                effectivePage, effectivePageSize);
        ChatRoomListResponseDTO chatRoomListResponseDTO = new ChatRoomListResponseDTO();
        chatRoomListResponseDTO.setPage(effectivePage);
        chatRoomListResponseDTO.setPageSize(effectivePageSize);
        // 会话列表不精确 COUNT（与 feed 索引路径同口径）：-1 = 未知，不为一个没人消费的数字多跑一次查询
        chatRoomListResponseDTO.setTotal(-1L);
        chatRoomListResponseDTO.setList(rooms.stream().map(this::toRoomListItemResponseDTO).toList());
        return Response.ok(chatRoomListResponseDTO);
    }

    @Override
    @GetMapping("/rooms/{roomId}/messages")
    public Response<List<ChatMessageResponseDTO>> listMessages(@PathVariable String roomId,
                                                               @RequestParam(required = false) Long beforeSeq,
                                                               @RequestParam(required = false) Integer limit) {
        List<MessageEntity> messages = chatCase.listMessages(roomId, SecurityUtils.currentUserId(),
                beforeSeq, limit);
        List<ChatMessageResponseDTO> dtos = messages.stream()
                .map(message -> toMessageResponseDTO(message, null))
                .toList();
        return Response.ok(fillSenderInfo(dtos));
    }

    @Override
    @GetMapping("/timeline")
    public Response<ChatTimelineResponseDTO> pullTimeline(
            // 对外参数名保持 afterDeliverySeq（既有契约），域内统一叫「投递序游标」
            @RequestParam(name = "afterDeliverySeq", required = false) Long deliverySeqCursor,
            @RequestParam(required = false) Integer limit) {
        TimelinePageVO timelinePageVO = chatCase.pullTimeline(SecurityUtils.currentUserId(),
                deliverySeqCursor, limit);
        List<ChatMessageResponseDTO> dtos = timelinePageVO.getItems().stream()
                .map(item -> toMessageResponseDTO(item.getMessage(), item.getDeliverySeq()))
                .toList();
        ChatTimelineResponseDTO chatTimelineResponseDTO = new ChatTimelineResponseDTO();
        chatTimelineResponseDTO.setList(fillSenderInfo(dtos));
        chatTimelineResponseDTO.setNextCursor(timelinePageVO.getNextCursor());
        chatTimelineResponseDTO.setHasMore(timelinePageVO.getHasMore());
        return Response.ok(chatTimelineResponseDTO);
    }

    @Override
    @PostMapping("/rooms/{roomId}/read")
    public Response<Long> markRead(@PathVariable String roomId) {
        return Response.ok(chatReadService.markRead(roomId, SecurityUtils.currentUserId()));
    }

    @Override
    @PostMapping("/rooms/{roomId}/messages/{seq}/recall")
    public Response<Void> recallMessage(@PathVariable String roomId, @PathVariable Long seq) {
        chatRecallService.recall(roomId, seq, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/rooms/{roomId}/hide")
    public Response<Void> hideRoom(@PathVariable String roomId) {
        chatReadService.hideRoom(roomId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/rooms/{roomId}/members")
    public Response<ChatMembersResponseDTO> listMembers(@PathVariable String roomId) {
        List<RoomMemberVO> members = chatCase.listMembers(roomId, SecurityUtils.currentUserId());
        ChatMembersResponseDTO chatMembersResponseDTO = new ChatMembersResponseDTO();
        chatMembersResponseDTO.setMemberCount(members.size());
        chatMembersResponseDTO.setMembers(members.stream().map(this::toMemberResponseDTO).toList());
        return Response.ok(chatMembersResponseDTO);
    }

    // ===== 装配 =====

    private MsgTypeEnum parseMsgType(String msgType) {
        try {
            return MsgTypeEnum.valueOf(msgType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "消息类型不合法");
        }
    }

    /** 会话来源可省略（首页/用户主页入口）；写了就必须是市集两域之一 */
    private RoomSourceEnum parseRoomSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return RoomSourceEnum.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "会话来源不合法");
        }
    }

    private CardRefTypeEnum parseRefType(String refType) {
        if (refType == null) {
            return null;
        }
        try {
            return CardRefTypeEnum.valueOf(refType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "卡片引用类型不合法");
        }
    }

    private ChatRoomListItemResponseDTO toRoomListItemResponseDTO(RoomListItemVO roomListItemVO) {
        ChatRoomListItemResponseDTO chatRoomListItemResponseDTO = new ChatRoomListItemResponseDTO();
        chatRoomListItemResponseDTO.setRoomId(roomListItemVO.getRoomId());
        chatRoomListItemResponseDTO.setPeerUserId(roomListItemVO.getPeerUserId());
        chatRoomListItemResponseDTO.setPeerNickname(roomListItemVO.getPeerNickname());
        chatRoomListItemResponseDTO.setPeerAvatar(roomListItemVO.getPeerAvatar());
        chatRoomListItemResponseDTO.setPeerRole(roomListItemVO.getPeerRole() == null ? null : roomListItemVO.getPeerRole().name());
        chatRoomListItemResponseDTO.setSource(roomListItemVO.getSource() == null ? null : roomListItemVO.getSource().name());
        chatRoomListItemResponseDTO.setUnread(roomListItemVO.getUnread());
        chatRoomListItemResponseDTO.setLastMsgType(roomListItemVO.getLastMsgType() == null ? null : roomListItemVO.getLastMsgType().name());
        chatRoomListItemResponseDTO.setLastMsgPreview(roomListItemVO.getLastMsgPreview());
        // JSON 字段名 lastMsgAt/lastReadSeq 是对外契约（chat-member-table-refactor 票 01：接口契约零变化），
        // 内部实体列名已随表翻成 lastMsgTime/readSeq，仅在装配处收敛
        chatRoomListItemResponseDTO.setLastMsgAt(roomListItemVO.getLastMsgTime());
        return chatRoomListItemResponseDTO;
    }

    private ChatMembersResponseDTO.ChatMemberResponseDTO toMemberResponseDTO(RoomMemberVO roomMemberVO) {
        ChatMembersResponseDTO.ChatMemberResponseDTO dto = new ChatMembersResponseDTO.ChatMemberResponseDTO();
        dto.setUserId(roomMemberVO.getUserId());
        dto.setNickname(roomMemberVO.getNickname());
        dto.setAvatar(roomMemberVO.getAvatar());
        dto.setLastReadSeq(roomMemberVO.getReadSeq());
        return dto;
    }

    /** 发送者昵称头像补齐：单条走批查同一口径（发送 200 只补一条发送者） */
    private ChatMessageResponseDTO fillSenderInfo(ChatMessageResponseDTO chatMessageResponseDTO, String fromUserId) {
        if (fromUserId == null) {
            return chatMessageResponseDTO;
        }
        fillSenderInfo(List.of(chatMessageResponseDTO));
        return chatMessageResponseDTO;
    }

    /**
     * 发送者昵称头像批查：唯一消费方是展示字段（昵称/头像两列），故走窄投影 listBriefByIds，
     * 不为一个昵称把 phone/credit_score 等敏感与无关列捞出库（见 UserMapper.briefColumns 口径）
     */
    private List<ChatMessageResponseDTO> fillSenderInfo(List<ChatMessageResponseDTO> dtos) {
        List<String> senderIds = dtos.stream()
                .map(ChatMessageResponseDTO::getFromUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (senderIds.isEmpty()) {
            return dtos;
        }
        Map<String, UserBriefVO> senders = userRepository.listBriefByIds(senderIds).stream()
                .collect(Collectors.toMap(UserBriefVO::getUserId, Function.identity()));
        for (ChatMessageResponseDTO chatMessageResponseDTO : dtos) {
            UserBriefVO userBriefVO = senders.get(chatMessageResponseDTO.getFromUserId());
            chatMessageResponseDTO.setFromNickname(userBriefVO == null ? null : userBriefVO.getNickname());
            chatMessageResponseDTO.setFromAvatar(userBriefVO == null ? null : userBriefVO.getAvatar());
        }
        return dtos;
    }

    private ChatMessageResponseDTO toMessageResponseDTO(MessageEntity messageEntity, Long deliverySeq) {
        ChatMessageResponseDTO chatMessageResponseDTO = new ChatMessageResponseDTO();
        chatMessageResponseDTO.setRoomId(messageEntity.getRoomId());
        chatMessageResponseDTO.setMessageId(messageEntity.getMessageId());
        chatMessageResponseDTO.setSeq(messageEntity.getSeq());
        chatMessageResponseDTO.setFromUserId(messageEntity.getFromUserId());
        chatMessageResponseDTO.setMsgType(messageEntity.getMsgType().name());
        chatMessageResponseDTO.setStatus(messageEntity.getStatus().name());
        chatMessageResponseDTO.setContent(messageEntity.getContent());
        chatMessageResponseDTO.setRefType(messageEntity.getRefType() == null ? null : messageEntity.getRefType().name());
        chatMessageResponseDTO.setRefId(messageEntity.getRefId());
        chatMessageResponseDTO.setClientMsgId(messageEntity.getClientMsgId());
        chatMessageResponseDTO.setDeliverySeq(deliverySeq);
        chatMessageResponseDTO.setCreatedAt(messageEntity.getCreatedAt());
        return chatMessageResponseDTO;
    }
}
